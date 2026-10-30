<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ChatDotRound,
  Cpu,
  DataBoard,
  Document,
  MagicStick,
  Monitor,
  Operation,
  Promotion,
  Setting,
  SwitchButton,
  Timer,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const title = computed(() => (typeof route.meta.title === 'string' ? route.meta.title : '控制台'))

function logout() {
  auth.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <div class="brand-icon">
          <el-icon :size="19"><ChatDotRound /></el-icon>
        </div>
        <div class="brand-copy">
          <strong>WeChat AI</strong>
          <span>Operations Console</span>
        </div>
      </div>

      <el-menu
        class="side-menu"
        :default-active="route.path"
        router
        background-color="transparent"
        text-color="#8fa2bd"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataBoard /></el-icon><span>运行概览</span>
        </el-menu-item>
        <el-menu-item index="/providers">
          <el-icon><Cpu /></el-icon><span>模型配置</span>
        </el-menu-item>
        <el-menu-item index="/bot">
          <el-icon><Setting /></el-icon><span>机器人配置</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Operation /></el-icon><span>运行策略</span>
        </el-menu-item>
        <el-menu-item index="/pacing">
          <el-icon><Timer /></el-icon><span>发信节奏</span>
        </el-menu-item>
        <el-menu-item index="/conversations">
          <el-icon><ChatDotRound /></el-icon><span>会话记录</span>
        </el-menu-item>
        <el-menu-item index="/rules">
          <el-icon><Promotion /></el-icon><span>自动回复</span>
        </el-menu-item>
        <el-menu-item index="/prompts">
          <el-icon><Document /></el-icon><span>提示词模板</span>
        </el-menu-item>
        <el-menu-item index="/scheduled">
          <el-icon><Timer /></el-icon><span>定时消息</span>
        </el-menu-item>
        <el-menu-item index="/debug">
          <el-icon><MagicStick /></el-icon><span>在线调试</span>
        </el-menu-item>
        <el-menu-item index="/diagnostics">
          <el-icon><Monitor /></el-icon><span>诊断中心</span>
        </el-menu-item>
      </el-menu>

      <div class="sidebar-foot">
        <span class="status-dot"></span>
        <span>本地运行</span>
      </div>
    </aside>

    <section class="workspace">
      <header class="topbar">
        <h2>{{ title }}</h2>
        <div class="topbar-actions">
          <button class="user-chip" type="button" @click="logout">
            <span class="avatar">A</span>
            <span class="user-copy"><strong>Administrator</strong><small>Admin Token</small></span>
            <el-icon><SwitchButton /></el-icon>
          </button>
        </div>
      </header>
      <main class="content">
        <RouterView v-slot="{ Component }">
          <Transition name="fade" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </section>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  min-height: 100vh;
}

.sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 20;
  display: flex;
  width: 204px;
  flex-direction: column;
  padding: 18px 10px 12px;
  border-right: 1px solid rgba(148, 163, 184, 0.12);
  background: #101827;
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 8px 20px;
}

.brand-icon {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 8px;
  color: #dbeafe;
  background: #2563eb;
}

.brand-copy strong,
.brand-copy span {
  display: block;
}

.brand-copy strong {
  color: #f8fafc;
  font-size: 13px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.brand-copy span {
  margin-top: 1px;
  color: #64748b;
  font-size: 9px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.side-menu {
  flex: 1;
  border: 0;
}

.side-menu :deep(.el-menu-item) {
  height: 38px;
  margin: 2px 0;
  border-radius: 7px;
  font-size: 12px;
}

.side-menu :deep(.el-menu-item:hover) {
  background: rgba(148, 163, 184, 0.08);
  color: #e2e8f0;
}

.side-menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background: rgba(59, 130, 246, 0.18);
  box-shadow: inset 2px 0 #60a5fa;
}

.sidebar-foot {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 9px 10px 4px;
  color: #64748b;
  font-size: 11px;
}

.sidebar-foot .status-dot {
  width: 6px;
  height: 6px;
  margin-right: 5px;
  box-shadow: none;
}

.workspace {
  width: calc(100% - 204px);
  min-height: 100vh;
  margin-left: 204px;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 15;
  display: flex;
  min-height: 54px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 24px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.96);
}

.topbar h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--ink);
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 3px 6px 3px 3px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  cursor: pointer;
  transition: 0.15s ease;
}

.user-chip:hover {
  border-color: var(--line);
  background: #fff;
}

.avatar {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 7px;
  color: #fff;
  background: #334155;
  font-size: 11px;
  font-weight: 700;
}

.user-copy {
  text-align: left;
}

.user-copy strong,
.user-copy small {
  display: block;
}

.user-copy strong {
  color: #334155;
  font-size: 11px;
}

.user-copy small {
  margin-top: 1px;
  color: #94a3b8;
  font-size: 9px;
}

.user-chip > .el-icon {
  color: #94a3b8;
  font-size: 12px;
}

.content {
  padding: 16px 24px 32px;
}

@media (max-width: 900px) {
  .sidebar {
    width: 64px;
    padding-right: 7px;
    padding-left: 7px;
  }

  .brand-copy,
  .side-menu :deep(.el-menu-item span),
  .sidebar-foot {
    display: none;
  }

  .sidebar-brand {
    justify-content: center;
    padding-right: 0;
    padding-left: 0;
  }

  .side-menu :deep(.el-menu-item) {
    justify-content: center;
    padding: 0 !important;
  }

  .workspace {
    width: calc(100% - 64px);
    margin-left: 64px;
  }

  .topbar {
    padding: 0 16px;
  }

  .content {
    padding: 14px 16px 24px;
  }

  .user-copy {
    display: none;
  }
}

@media (max-width: 560px) {
  .topbar {
    min-height: 50px;
  }

  .content {
    padding: 12px 12px 22px;
  }
}
</style>


