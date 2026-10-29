<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Key } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { getErrorMessage } from '@/api/client'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const token = ref('')
const loading = ref(false)

async function submit() {
  if (!token.value.trim()) {
    ElMessage.warning('请输入管理端 Token')
    return
  }
  loading.value = true
  try {
    await auth.login(token.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.replace(redirect)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-shell">
    <section class="login-card">
      <div class="brand">
        <span class="brand-mark"><el-icon :size="20"><ChatDotRound /></el-icon></span>
        <div>
          <strong>WeChat AI</strong>
          <span>Operations Console</span>
        </div>
      </div>

      <h1>登录管理后台</h1>
      <p class="login-desc">使用后端 <code>ADMIN_TOKEN</code> 中的值登录。</p>

      <el-input
        v-model="token"
        size="large"
        type="password"
        show-password
        placeholder="Admin Token"
        :prefix-icon="Key"
        @keyup.enter="submit"
      />

      <el-button class="login-button" type="primary" size="large" :loading="loading" @click="submit">
        进入控制台
      </el-button>

      <p class="login-note">Token 只保存在本机浏览器，不会上传到第三方。</p>
    </section>
  </main>
</template>

<style scoped>
.login-shell {
  display: grid;
  min-height: 100vh;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(circle at 50% -10%, rgba(59, 130, 246, 0.1), transparent 45%),
    #f4f6fb;
}

.login-card {
  width: min(380px, 100%);
  padding: 32px 30px 26px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.08);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 26px;
}

.brand-mark {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 10px;
  color: #eff6ff;
  background: linear-gradient(145deg, #2563eb, #0ea5e9);
}

.brand strong {
  display: block;
  color: var(--ink);
  font-size: var(--fs-md);
  font-weight: 650;
  letter-spacing: -0.01em;
}

.brand span {
  display: block;
  margin-top: 1px;
  color: var(--muted-soft);
  font-size: 11px;
  letter-spacing: 0.04em;
}

h1 {
  margin: 0;
  color: var(--ink);
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.login-desc {
  margin: 8px 0 20px;
  color: var(--muted);
  font-size: var(--fs-sm);
  line-height: 1.7;
}

.login-desc code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #f1f5f9;
  color: var(--ink-soft);
  font-size: 12px;
}

.login-button {
  width: 100%;
  margin-top: 14px;
}

.login-note {
  margin: 16px 0 0;
  color: var(--muted-soft);
  font-size: var(--fs-xs);
  text-align: center;
}
</style>
