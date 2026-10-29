<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, ChatDotRound, Connection, Key, MagicStick } from '@element-plus/icons-vue'
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
    <section class="login-hero">
      <div class="brand-mark">
        <el-icon :size="22"><ChatDotRound /></el-icon>
      </div>
      <div class="hero-copy">
        <span class="eyebrow">WINDOWS · WECHAT · AI</span>
        <h1>把微信对话<br />交给可控的 AI 工作流</h1>
        <p>统一管理微信接入、模型提供方、对话上下文与回复审计。兼容 DeepSeek 及所有 OpenAI Chat Completions 规范服务。</p>
      </div>
      <div class="feature-list">
        <div class="feature">
          <el-icon><MagicStick /></el-icon>
          <div><strong>模型随切随用</strong><span>DeepSeek、千问、Moonshot、OpenAI</span></div>
        </div>
        <div class="feature">
          <el-icon><Connection /></el-icon>
          <div><strong>Windows 适配层解耦</strong><span>业务不绑定 Hook / UI 自动化实现</span></div>
        </div>
        <div class="feature">
          <el-icon><Key /></el-icon>
          <div><strong>密钥加密落库</strong><span>API Key 不回传前端，支持提供方回退</span></div>
        </div>
      </div>
      <div class="hero-orbit orbit-a"></div>
      <div class="hero-orbit orbit-b"></div>
    </section>

    <section class="login-panel">
      <div class="login-card">
        <span class="panel-eyebrow">ADMIN CONSOLE</span>
        <h2>登录管理后台</h2>
        <p class="panel-desc">使用后端环境变量 <code>ADMIN_TOKEN</code> 中的值登录。</p>
        <el-input
          v-model="token"
          size="large"
          type="password"
          show-password
          placeholder="请输入 Admin Token"
          :prefix-icon="Key"
          @keyup.enter="submit"
        />
        <el-button class="login-button" type="primary" size="large" :loading="loading" @click="submit">
          进入控制台
          <el-icon class="el-icon--right"><ArrowRight /></el-icon>
        </el-button>
        <div class="login-note">
          <span class="status-dot"></span>
          生产环境请替换默认 Token，并接入正式账号体系。
        </div>
      </div>
      <p class="copyright">WeChat AI Console · Local-first operations</p>
    </section>
  </main>
</template>

<style scoped>
.login-shell {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(420px, 0.85fr);
  min-height: 100vh;
  background: #f6f8fc;
}

.login-hero {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 100vh;
  overflow: hidden;
  padding: 54px 7vw;
  color: #f8fafc;
  background:
    radial-gradient(circle at 70% 15%, rgba(59, 130, 246, 0.32), transparent 30%),
    radial-gradient(circle at 15% 92%, rgba(14, 165, 233, 0.18), transparent 35%),
    linear-gradient(145deg, #0b1220, #111c33 58%, #0c172b);
}

.brand-mark {
  position: relative;
  z-index: 2;
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(12px);
}

.hero-copy,
.feature-list {
  position: relative;
  z-index: 2;
}

.hero-copy {
  max-width: 660px;
  margin: auto 0;
  padding: 56px 0;
}

.eyebrow,
.panel-eyebrow {
  color: #60a5fa;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.17em;
}

.hero-copy h1 {
  margin: 20px 0 24px;
  font-size: clamp(42px, 5vw, 70px);
  font-weight: 760;
  line-height: 1.08;
  letter-spacing: -0.055em;
}

.hero-copy p {
  max-width: 590px;
  margin: 0;
  color: #9fb0ca;
  font-size: 15px;
  line-height: 1.9;
}

.feature-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.feature {
  display: flex;
  gap: 12px;
  padding: 16px;
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.045);
  backdrop-filter: blur(8px);
}

.feature .el-icon {
  flex: 0 0 auto;
  margin-top: 2px;
  color: #60a5fa;
}

.feature strong,
.feature span {
  display: block;
}

.feature strong {
  margin-bottom: 5px;
  font-size: 13px;
}

.feature span {
  color: #8497b3;
  font-size: 12px;
  line-height: 1.5;
}

.hero-orbit {
  position: absolute;
  border: 1px solid rgba(96, 165, 250, 0.12);
  border-radius: 50%;
}

.orbit-a {
  top: -18%;
  right: -22%;
  width: 680px;
  height: 680px;
}

.orbit-b {
  right: 2%;
  bottom: -42%;
  width: 480px;
  height: 480px;
}

.login-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 42px;
}

.login-card {
  width: min(100%, 430px);
  padding: 40px;
  border: 1px solid #e5ebf4;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 30px 80px rgba(15, 23, 42, 0.1);
  backdrop-filter: blur(16px);
}

.login-card h2 {
  margin: 10px 0 8px;
  font-size: 27px;
  letter-spacing: -0.04em;
}

.panel-desc {
  margin: 0 0 28px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.7;
}

.panel-desc code {
  padding: 2px 5px;
  border-radius: 5px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
}

.login-button {
  width: 100%;
  margin-top: 16px;
  letter-spacing: 0.02em;
}

.login-note {
  margin-top: 22px;
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.6;
}

.copyright {
  margin: 28px 0 0;
  color: #94a3b8;
  font-size: 12px;
}

@media (max-width: 980px) {
  .login-shell {
    grid-template-columns: 1fr;
  }

  .login-hero {
    min-height: auto;
    padding: 34px 28px 42px;
  }

  .hero-copy {
    margin: 40px 0;
    padding: 0;
  }

  .hero-copy h1 {
    font-size: 42px;
  }

  .feature-list {
    display: none;
  }

  .login-panel {
    padding: 34px 20px 46px;
  }
}

@media (max-width: 560px) {
  .hero-copy h1 {
    font-size: 35px;
  }

  .login-card {
    padding: 28px 24px;
  }
}
</style>
