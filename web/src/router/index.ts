import { createRouter, createWebHistory } from 'vue-router'
import { TOKEN_KEY } from '@/api/client'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/',
      component: () => import('@/layouts/AppLayout.vue'),
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '运行概览' },
        },
        {
          path: 'providers',
          name: 'providers',
          component: () => import('@/views/ProvidersView.vue'),
          meta: { title: '模型配置' },
        },
        {
          path: 'bot',
          name: 'bot',
          component: () => import('@/views/BotSettingsView.vue'),
          meta: { title: '机器人配置' },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          meta: { title: '运行策略' },
        },
        {
          path: 'conversations',
          name: 'conversations',
          component: () => import('@/views/ConversationsView.vue'),
          meta: { title: '会话记录' },
        },
        {
          path: 'rules',
          name: 'rules',
          component: () => import('@/views/RulesView.vue'),
          meta: { title: '自动回复' },
        },
        {
          path: 'prompts',
          name: 'prompts',
          component: () => import('@/views/PromptTemplatesView.vue'),
          meta: { title: '提示词模板' },
        },
        {
          path: 'scheduled',
          name: 'scheduled',
          component: () => import('@/views/ScheduledView.vue'),
          meta: { title: '定时消息' },
        },
        {
          path: 'diagnostics',
          name: 'diagnostics',
          component: () => import('@/views/DiagnosticsView.vue'),
          meta: { title: '诊断中心' },
        },
        {
          path: 'debug',
          name: 'debug',
          component: () => import('@/views/DebugView.vue'),
          meta: { title: '在线调试' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/dashboard',
    },
  ],
})

router.beforeEach((to) => {
  const hasToken = Boolean(localStorage.getItem(TOKEN_KEY))
  if (!hasToken && to.name !== 'login') {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (hasToken && to.name === 'login') {
    return { name: 'dashboard' }
  }
  return true
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title ? `${title} · WeChat AI Console` : 'WeChat AI Console'
})

export default router
