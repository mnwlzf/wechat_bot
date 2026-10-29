param(
    [string]$LogPath = "D:\wechat_bot\logs\dbgwin.log",
    [int]$Seconds = 180
)

$source = @'
using System;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Text;
using System.Threading;

public static class DbgWin
{
    public static void Capture(string logPath, int seconds)
    {
        var ready = new EventWaitHandle(false, EventResetMode.AutoReset, "DBWIN_BUFFER_READY");
        var dataReady = new EventWaitHandle(false, EventResetMode.AutoReset, "DBWIN_DATA_READY");
        using (var mmf = MemoryMappedFile.CreateOrOpen("DBWIN_BUFFER", 4096))
        using (var view = mmf.CreateViewAccessor())
        using (var sw = new StreamWriter(logPath, false, new UTF8Encoding(false)))
        {
            var deadline = DateTime.UtcNow.AddSeconds(seconds);
            while (DateTime.UtcNow < deadline)
            {
                ready.Set();
                if (!dataReady.WaitOne(500)) continue;
                int pid = view.ReadInt32(0);
                var sb = new StringBuilder();
                for (int i = 4; i < 4096; i++)
                {
                    byte b = view.ReadByte(i);
                    if (b == 0) break;
                    sb.Append((char)b);
                }
                sw.WriteLine(pid.ToString() + ": " + sb.ToString());
                sw.Flush();
            }
        }
    }
}
'@

Add-Type -TypeDefinition $source -Language CSharp
[DbgWin]::Capture($LogPath, $Seconds)
