using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

// Drives wx_key.dll (from the WeChatAgent installer) to capture the SQLCipher
// passphrase of the running WeChat 4.x install.
//
// The DLL exports:
//   bool InitializeHook(uint32 pid)
//   bool PollKeyData(char* buffer, int capacity)
//   bool CleanupHook()
//   bool GetStatusMessage(char* buffer, int capacity, int* level)
//   char* GetLastErrorMsg()
//
// The key is only available when WeChat opens its database, so the hook must be
// installed before / while the user logs in.
public static class WxKeyCapture {
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Ansi)]
    static extern IntPtr LoadLibrary(string path);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Ansi)]
    static extern IntPtr GetProcAddress(IntPtr module, string name);
    [DllImport("kernel32.dll")]
    static extern bool FreeLibrary(IntPtr module);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate bool InitHook(uint pid);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate bool PollKey(IntPtr buffer, int capacity);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate bool Cleanup();
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate bool Status(IntPtr buffer, int capacity, out int level);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate IntPtr LastError();

    static byte[] Hex(string value) {
        byte[] bytes = new byte[value.Length / 2];
        for (int i = 0; i < bytes.Length; i++) bytes[i] = Convert.ToByte(value.Substring(i * 2, 2), 16);
        return bytes;
    }

    static byte[] Pbkdf2(byte[] password, byte[] salt, int iterations, int length) {
        using (var hmac = new HMACSHA512(password)) {
            byte[] result = new byte[length];
            byte[] input = new byte[salt.Length + 4];
            Buffer.BlockCopy(salt, 0, input, 0, salt.Length);
            input[salt.Length + 3] = 1;
            byte[] u = hmac.ComputeHash(input);
            byte[] acc = (byte[])u.Clone();
            for (int i = 1; i < iterations; i++) {
                u = hmac.ComputeHash(u);
                for (int k = 0; k < acc.Length; k++) acc[k] ^= u[k];
            }
            Buffer.BlockCopy(acc, 0, result, 0, Math.Min(acc.Length, length));
            return result;
        }
    }

    static bool ProcessAlive(uint pid) {
        try {
            using (var process = System.Diagnostics.Process.GetProcessById((int)pid)) {
                return !process.HasExited;
            }
        } catch (ArgumentException) {
            return false;
        } catch (InvalidOperationException) {
            return false;
        }
    }

    // Reads page 1 of a SQLCipher database (shared read: WeChat keeps it open).
    static byte[] Page(string dbPath) {
        using (var fs = new FileStream(dbPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite)) {
            byte[] page = new byte[4096];
            int read = 0;
            while (read < 4096) {
                int n = fs.Read(page, read, 4096 - read);
                if (n <= 0) break;
                read += n;
            }
            if (read < 4096) throw new Exception("page 1 too short");
            return page;
        }
    }

    // Verifies a candidate passphrase against the HMAC of page 1.
    public static bool VerifyPassphrase(string passphraseHex, string dbPath) {
        byte[] page = Page(dbPath);
        byte[] salt = new byte[16];
        Buffer.BlockCopy(page, 0, salt, 0, 16);
        byte[] macSalt = new byte[16];
        for (int i = 0; i < 16; i++) macSalt[i] = (byte)(salt[i] ^ 0x3a);
        byte[] key = Pbkdf2(Hex(passphraseHex), salt, 256000, 32);
        byte[] macKey = Pbkdf2(key, macSalt, 2, 32);
        int bodyEnd = 4096 - 80 + 16;
        byte[] message = new byte[bodyEnd - 16 + 4];
        Buffer.BlockCopy(page, 16, message, 0, bodyEnd - 16);
        message[bodyEnd - 16] = 1;
        using (var hmac = new HMACSHA512(macKey)) {
            byte[] mac = hmac.ComputeHash(message);
            for (int i = 0; i < 64; i++) if (mac[i] != page[bodyEnd + i]) return false;
            return true;
        }
    }

    /// <summary>
    /// Hooks WeChat and waits up to timeoutSeconds for the database passphrase.
    /// Returns the verified 64-hex key, or null. Status/error lines go to log.
    /// </summary>
    public static string Capture(string dllPath, uint pid, string dbPath, int timeoutSeconds, ref string log) {
        var text = new StringBuilder();
        try {
            IntPtr module = LoadLibrary(dllPath);
            if (module == IntPtr.Zero) {
                text.AppendLine("LoadLibrary failed: " + Marshal.GetLastWin32Error());
                log = text.ToString();
                return null;
            }
            try {
                IntPtr initPtr = GetProcAddress(module, "InitializeHook");
                IntPtr pollPtr = GetProcAddress(module, "PollKeyData");
                IntPtr cleanPtr = GetProcAddress(module, "CleanupHook");
                IntPtr statusPtr = GetProcAddress(module, "GetStatusMessage");
                IntPtr errPtr = GetProcAddress(module, "GetLastErrorMsg");
                if (initPtr == IntPtr.Zero || pollPtr == IntPtr.Zero || cleanPtr == IntPtr.Zero) {
                    text.AppendLine("wx_key.dll is missing exports");
                    log = text.ToString();
                    return null;
                }
                var init = (InitHook)Marshal.GetDelegateForFunctionPointer(initPtr, typeof(InitHook));
                var poll = (PollKey)Marshal.GetDelegateForFunctionPointer(pollPtr, typeof(PollKey));
                var cleanup = (Cleanup)Marshal.GetDelegateForFunctionPointer(cleanPtr, typeof(Cleanup));
                Status status = statusPtr == IntPtr.Zero
                    ? null
                    : (Status)Marshal.GetDelegateForFunctionPointer(statusPtr, typeof(Status));

                if (!init(pid)) {
                    string message = "";
                    if (errPtr != IntPtr.Zero) {
                        var lastError = (LastError)Marshal.GetDelegateForFunctionPointer(errPtr, typeof(LastError));
                        IntPtr pointer = lastError();
                        if (pointer != IntPtr.Zero) message = Marshal.PtrToStringAnsi(pointer) ?? "";
                    }
                    text.AppendLine("InitializeHook failed: " + message);
                    log = text.ToString();
                    return null;
                }
                text.AppendLine("hook installed on pid " + pid);

                IntPtr keyBuffer = Marshal.AllocHGlobal(128);
                IntPtr statusBuffer = Marshal.AllocHGlobal(1024);
                try {
                    // timeoutSeconds <= 0 means "wait until the key shows up or WeChat exits"
                    DateTime deadline = timeoutSeconds > 0
                        ? DateTime.UtcNow.AddSeconds(timeoutSeconds)
                        : DateTime.MaxValue;
                    var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                    int polls = 0;
                    while (DateTime.UtcNow < deadline) {
                        if (!ProcessAlive(pid)) {
                            text.AppendLine("target process exited");
                            break;
                        }
                        if (poll(keyBuffer, 128)) {
                            string candidate = Marshal.PtrToStringAnsi(keyBuffer) ?? "";
                            if (System.Text.RegularExpressions.Regex.IsMatch(candidate, "^[0-9a-fA-F]{64}$")
                                && seen.Add(candidate)) {
                                bool verified = string.IsNullOrEmpty(dbPath)
                                    || VerifyPassphrase(candidate, dbPath);
                                text.AppendLine("candidate verified=" + verified);
                                if (verified) {
                                    log = text.ToString();
                                    return candidate.ToLowerInvariant();
                                }
                            }
                        }
                        if (status != null) {
                            for (int i = 0; i < 8; i++) {
                                int level;
                                if (!status(statusBuffer, 1024, out level)) break;
                                string line = Marshal.PtrToStringAnsi(statusBuffer) ?? "";
                                if (line.Length > 0) text.AppendLine("status[" + level + "]: " + line);
                            }
                        }
                        polls++;
                        System.Threading.Thread.Sleep(120);
                    }
                    text.AppendLine("timeout after " + polls + " polls");
                } finally {
                    Marshal.FreeHGlobal(keyBuffer);
                    Marshal.FreeHGlobal(statusBuffer);
                    cleanup();
                }
            } finally {
                FreeLibrary(module);
            }
        } catch (Exception ex) {
            text.AppendLine("exception: " + ex.Message);
        }
        log = text.ToString();
        return null;
    }
}
