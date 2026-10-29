<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ChatDotRound,
  Cpu,
  DataBoard,
  MagicStick,
  Operation,
  Setting,
  SwitchButton,
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
        <el-menu-item index="/conversations">
          <el-icon><ChatDotRound /></el-icon><span>会话记录</span>
        </el-menu-item>
        <el-menu-item index="/debug">
          <el-icon><MagicStick /></el-icon><span>在线调试</span>
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
          <span class="env-badge">LOCAL</span>
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
  width: 238px;
  flex-direction: column;
  padding: 26px 16px 18px;
  border-right: 1px solid rgba(148, 163, 184, 0.09);
  background:
    radial-gradient(circle at 20% 0%, rgba(59, 130, 246, 0.18), transparent 25%),
    #0b1220;
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 10px 26px;
}

.brand-icon {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 12px;
  color: #bfdbfe;
  background: linear-gradient(145deg, #1d4ed8, #0ea5e9);
  box-shadow: 0 8px 24px rgba(37, 99, 235, 0.34);
}

.brand-copy strong,
.brand-copy span {
  display: block;
}

.brand-copy strong {
  color: #f8fafc;
  font-size: 14px;
  letter-spacing: -0.01em;
}

.brand-copy span {
  margin-top: 3px;
  color: #64748b;
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.side-menu {
  flex: 1;
  border: 0;
}

.side-menu :deep(.el-menu-item) {
  height: 44px;
  margin: 4px 0;
  border-radius: 10px;
  font-size: 13px;
}

.side-menu :deep(.el-menu-item:hover) {
  background: rgba(148, 163, 184, 0.08);
  color: #dbeafe;
}

.side-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, rgba(37, 99, 235, 0.25), rgba(37, 99, 235, 0.08));
  box-shadow: inset 2px 0 #60a5fa;
}

.sidebar-foot {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 12px 14px;
  color: #64748b;
  font-size: 12px;
}

.sidebar-foot .status-dot {
  margin-right: 6px;
}

.workspace {
  width: calc(100% - 238px);
  min-height: 100vh;
  margin-left: 238px;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 15;
  display: flex;
  min-height: 68px;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 0 32px;
  border-bottom: 1px solid rgba(226, 232, 240, 0.8);
  background: rgba(244, 246, 251, 0.88);
  backdrop-filter: blur(16px);
}

.topbar h2 {
  margin: 0;
  font-size: 17px;
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--ink);
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.env-badge {
  padding: 5px 8px;
  border: 1px solid #bfdbfe;
  border-radius: 6px;
  color: #2563eb;
  background: #eff6ff;
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.1em;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 5px 8px 5px 5px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  cursor: pointer;
  transition: 0.18s ease;
}

.user-chip:hover {
  border-color: #dbe4f0;
  background: #fff;
}

.avatar {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 10px;
  color: #fff;
  background: linear-gradient(145deg, #0f172a, #334155);
  font-size: 13px;
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
  font-size: 12px;
}

.user-copy small {
  margin-top: 2px;
  color: #94a3b8;
  font-size: 11px;
}

.user-chip > .el-icon {
  color: #94a3b8;
  font-size: 13px;
}

.content {
  padding: 28px 32px 48px;
}

@media (max-width: 900px) {
  .sidebar {
    width: 78px;
    padding-right: 10px;
    padding-left: 10px;
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
    width: calc(100% - 78px);
    margin-left: 78px;
  }

  .topbar {
    padding: 0 22px;
  }

  .content {
    padding: 24px 22px 40px;
  }

  .user-copy {
    display: none;
  }
}

@media (max-width: 560px) {
  .topbar {
    min-height: 60px;
  }

  .env-badge {
    display: none;
  }

  .content {
    padding: 18px 14px 32px;
  }
}
</style>

