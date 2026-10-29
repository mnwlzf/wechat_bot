<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ChatLineRound, Cpu, MagicStick, Setting } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { Bot, BotInput, Provider } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const bot = ref<Bot | null>(null)
const providers = ref<Provider[]>([])
const formRef = ref<FormInstance>()

const form = reactive<BotInput>({
  name: '',
  enabled: true,
  modelProviderId: undefined,
  systemPrompt: '',
  replyPrivate: true,
  replyGroup: false,
  groupWakeWords: '@机器人',
  contextWindow: 20,
  temperature: 0.7,
  maxTokens: 800,
  rateLimitPerMinute: 20,
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入机器人名称', trigger: 'blur' }],
  systemPrompt: [{ required: true, message: '请输入系统提示词', trigger: 'blur' }],
}

const selectedProvider = computed(() => providers.value.find((item) => item.id === form.modelProviderId))
const isReady = computed(() => Boolean(bot.value?.enabled && form.modelProviderId))

async function load() {
  loading.value = true
  try {
    const [bots, providerList] = await Promise.all([adminApi.bots(), adminApi.providers()])
    providers.value = providerList
    bot.value = bots[0] || null
    if (bot.value) {
      Object.assign(form, {
        name: bot.value.name,
        enabled: bot.value.enabled,
        modelProviderId: bot.value.modelProviderId,
        systemPrompt: bot.value.systemPrompt,
        replyPrivate: bot.value.replyPrivate,
        replyGroup: bot.value.replyGroup,
        groupWakeWords: bot.value.groupWakeWords,
        contextWindow: bot.value.contextWindow,
        temperature: bot.value.temperature,
        maxTokens: bot.value.maxTokens,
        rateLimitPerMinute: bot.value.rateLimitPerMinute,
      })
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!bot.value || !formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const updated = await adminApi.updateBot(bot.value.id, {
      name: form.name,
      enabled: form.enabled,
      modelProviderId: form.modelProviderId,
      systemPrompt: form.systemPrompt,
      replyPrivate: form.replyPrivate,
      replyGroup: form.replyGroup,
      groupWakeWords: form.groupWakeWords,
      contextWindow: form.contextWindow,
      temperature: form.temperature,
      maxTokens: form.maxTokens,
      rateLimitPerMinute: form.rateLimitPerMinute,
    })
    bot.value = updated
    ElMessage.success('机器人配置已保存')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>机器人配置</h1>
        <p>控制回复范围、群聊唤醒词、上下文长度以及模型生成参数。</p>
      </div>
      <div class="head-actions">
        <el-button :disabled="!bot" @click="router.push('/debug')">测试对话</el-button>
        <el-button type="primary" :loading="saving" :disabled="!bot" @click="save">保存配置</el-button>
      </div>
    </div>

    <div v-if="!bot && !loading" class="empty-state surface-card">
      <strong>未找到机器人配置</strong>
      请确认后端已执行 Flyway 初始化脚本。
    </div>

    <div v-else class="settings-layout">
      <aside class="summary-stack">
        <section class="bot-card">
          <div class="bot-avatar"><el-icon :size="25"><ChatLineRound /></el-icon></div>
          <span class="bot-kicker">ACTIVE BOT</span>
          <h2>{{ form.name || '微信机器人' }}</h2>
          <p>{{ form.enabled ? '机器人配置已启用，等待 Agent 接入。' : '机器人当前已停用。' }}</p>
          <div class="bot-state">
            <span class="state-dot" :class="{ off: !form.enabled }"></span>
            {{ form.enabled ? 'Enabled' : 'Disabled' }}
          </div>
          <div class="summary-divider"></div>
          <dl>
            <div><dt>模型提供方</dt><dd>{{ selectedProvider?.name || '未选择' }}</dd></div>
            <div><dt>模型</dt><dd>{{ selectedProvider?.model || '—' }}</dd></div>
            <div><dt>上下文</dt><dd>{{ form.contextWindow }} 条</dd></div>
            <div><dt>频率限制</dt><dd>{{ form.rateLimitPerMinute }} / 分钟</dd></div>
          </dl>
        </section>

        <section class="health-card surface-card">
          <div class="health-head">
            <el-icon><MagicStick /></el-icon>
            <strong>配置检查</strong>
          </div>
          <div class="health-row">
            <span>模型已选择</span><el-tag :type="form.modelProviderId ? 'success' : 'warning'" size="small" effect="light">{{ form.modelProviderId ? '完成' : '待处理' }}</el-tag>
          </div>
          <div class="health-row">
            <span>机器人已启用</span><el-tag :type="form.enabled ? 'success' : 'info'" size="small" effect="light">{{ form.enabled ? '完成' : '停用' }}</el-tag>
          </div>
          <div class="health-row">
            <span>整体状态</span><el-tag :type="isReady ? 'success' : 'warning'" size="small" effect="light">{{ isReady ? '可对话' : '需配置' }}</el-tag>
          </div>
        </section>
      </aside>

      <section class="surface-card settings-card">
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
          <div class="section-block">
            <div class="section-title">
              <span class="section-icon"><el-icon><Setting /></el-icon></span>
              <div><h3>基础设置</h3><p>机器人的展示名称、开关与模型路由。</p></div>
            </div>
            <div class="form-grid">
              <el-form-item label="机器人名称" prop="name">
                <el-input v-model="form.name" placeholder="例如 客服小助手" />
              </el-form-item>
              <el-form-item label="启用状态">
                <div class="switch-field">
                  <el-switch v-model="form.enabled" />
                  <span>{{ form.enabled ? '接收并处理消息' : '暂停所有自动回复' }}</span>
                </div>
              </el-form-item>
            </div>
            <el-form-item label="主模型提供方">
              <el-select v-model="form.modelProviderId" clearable placeholder="选择模型提供方" style="width: 100%">
                <el-option
                  v-for="provider in providers"
                  :key="provider.id"
                  :label="`${provider.name} · ${provider.model}`"
                  :value="provider.id"
                  :disabled="!provider.enabled"
                />
              </el-select>
              <div class="field-hint">主模型不可用时，会按优先级回退到其他已启用模型。</div>
            </el-form-item>
          </div>

          <div class="section-block">
            <div class="section-title">
              <span class="section-icon"><el-icon><Cpu /></el-icon></span>
              <div><h3>回复策略</h3><p>决定哪些聊天会触发 AI，以及如何识别群聊请求。</p></div>
            </div>
            <div class="toggle-grid">
              <div class="toggle-card">
                <div><strong>私聊自动回复</strong><span>一对一聊天收到消息后立即处理</span></div>
                <el-switch v-model="form.replyPrivate" />
              </div>
              <div class="toggle-card">
                <div><strong>群聊自动回复</strong><span>仅在命中唤醒词时调用模型</span></div>
                <el-switch v-model="form.replyGroup" />
              </div>
            </div>
            <el-form-item label="群聊唤醒词" prop="groupWakeWords">
              <el-input v-model="form.groupWakeWords" placeholder="@机器人,小助手">
                <template #append>逗号分隔</template>
              </el-input>
              <div class="field-hint">留空时群聊所有消息都会触发回复，建议始终配置明确唤醒词。</div>
            </el-form-item>
          </div>

          <div class="section-block">
            <div class="section-title">
              <span class="section-icon"><el-icon><MagicStick /></el-icon></span>
              <div><h3>模型参数</h3><p>控制上下文窗口、输出长度、随机性与频率限制。</p></div>
            </div>
            <div class="form-grid three">
              <el-form-item label="上下文消息数">
                <el-input-number v-model="form.contextWindow" :min="1" :max="100" controls-position="right" />
              </el-form-item>
              <el-form-item label="Temperature">
                <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="1" controls-position="right" />
              </el-form-item>
              <el-form-item label="最大输出 Tokens">
                <el-input-number v-model="form.maxTokens" :min="1" :max="16000" :step="100" controls-position="right" />
              </el-form-item>
            </div>
            <el-form-item label="每会话每分钟消息上限">
              <el-input-number v-model="form.rateLimitPerMinute" :min="1" :max="600" controls-position="right" />
              <div class="field-hint">由 Redis 原子计数实现，超过阈值的消息会被忽略。</div>
            </el-form-item>
          </div>

          <div class="section-block prompt-block">
            <div class="section-title">
              <span class="section-icon"><el-icon><ChatLineRound /></el-icon></span>
              <div><h3>系统提示词</h3><p>定义机器人身份、语气、边界与回答格式。</p></div>
            </div>
            <el-form-item prop="systemPrompt">
              <el-input
                v-model="form.systemPrompt"
                type="textarea"
                :rows="8"
                maxlength="20000"
                show-word-limit
                placeholder="你是一个友好、简洁、可靠的微信助手……"
              />
            </el-form-item>
          </div>

          <div class="form-footer">
            <span>修改后点击保存立即生效，后续消息将使用新配置。</span>
            <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
          </div>
        </el-form>
      </section>
    </div>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  gap: 9px;
}

.settings-layout {
  display: grid;
  grid-template-columns: 292px minmax(0, 1fr);
  align-items: start;
  gap: 18px;
}

.summary-stack {
  position: sticky;
  top: 108px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.bot-card {
  padding: 26px;
  border-radius: 18px;
  color: #e2e8f0;
  background:
    radial-gradient(circle at 90% 0%, rgba(56, 189, 248, 0.22), transparent 32%),
    linear-gradient(150deg, #0b1220, #162642);
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.14);
}

.bot-avatar {
  display: grid;
  width: 54px;
  height: 54px;
  margin-bottom: 22px;
  place-items: center;
  border: 1px solid rgba(147, 197, 253, 0.2);
  border-radius: 16px;
  color: #bfdbfe;
  background: rgba(59, 130, 246, 0.16);
}

.bot-kicker {
  color: #60a5fa;
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.15em;
}

.bot-card h2 {
  margin: 9px 0 8px;
  color: #f8fafc;
  font-size: 21px;
  letter-spacing: -0.03em;
}

.bot-card > p {
  margin: 0;
  color: #8192ad;
  font-size: 12px;
  line-height: 1.7;
}

.bot-state {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 18px;
  color: #86efac;
  font-size: 12px;
}

.state-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #4ade80;
  box-shadow: 0 0 0 4px rgba(74, 222, 128, 0.1);
}

.state-dot.off {
  background: #94a3b8;
  box-shadow: 0 0 0 4px rgba(148, 163, 184, 0.1);
}

.summary-divider {
  height: 1px;
  margin: 22px 0;
  background: rgba(148, 163, 184, 0.12);
}

.bot-card dl {
  margin: 0;
}

.bot-card dl > div {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  padding: 8px 0;
  font-size: 12px;
}

.bot-card dt { color: #64748b; }
.bot-card dd { margin: 0; color: #cbd5e1; text-align: right; }

.health-card {
  padding: 18px;
}

.health-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  color: #334155;
  font-size: 13px;
}

.health-head .el-icon { color: #3b82f6; }

.health-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 0;
  color: #64748b;
  font-size: 12px;
}

.settings-card {
  padding: 6px 28px 0;
}

.section-block {
  padding: 28px 0;
  border-bottom: 1px solid #eef2f7;
}

.section-block:last-of-type {
  border-bottom: 0;
}

.section-title {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 22px;
}

.section-icon {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 9px;
  color: #2563eb;
  background: #eff6ff;
}

.section-title h3 {
  margin: 1px 0 4px;
  color: #1e293b;
  font-size: 14px;
}

.section-title p {
  margin: 0;
  color: #94a3b8;
  font-size: 12px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}

.form-grid.three {
  grid-template-columns: repeat(3, 1fr);
}

.switch-field {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
}

.switch-field span {
  color: #64748b;
  font-size: 12px;
}

.field-hint {
  margin-top: 5px;
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.5;
}

.toggle-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 20px;
}

.toggle-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 16px;
  border: 1px solid #e8eef6;
  border-radius: 12px;
  background: #fafcff;
}

.toggle-card strong,
.toggle-card span {
  display: block;
}

.toggle-card strong {
  color: #334155;
  font-size: 13px;
}

.toggle-card span {
  margin-top: 4px;
  color: #94a3b8;
  font-size: 12px;
}

.prompt-block {
  padding-bottom: 20px;
}

.form-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin: 0 -28px;
  padding: 18px 28px;
  border-top: 1px solid #eef2f7;
  border-radius: 0 0 16px 16px;
  background: #fbfcfe;
}

.form-footer span {
  color: #94a3b8;
  font-size: 12px;
}

:deep(.el-input-number) {
  width: 100%;
}

@media (max-width: 1050px) {
  .settings-layout {
    grid-template-columns: 1fr;
  }

  .summary-stack {
    position: static;
    display: grid;
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 700px) {
  .summary-stack,
  .form-grid,
  .form-grid.three,
  .toggle-grid {
    grid-template-columns: 1fr;
  }

  .settings-card {
    padding-right: 18px;
    padding-left: 18px;
  }

  .form-footer {
    align-items: stretch;
    flex-direction: column;
    margin-right: -18px;
    margin-left: -18px;
    padding-right: 18px;
    padding-left: 18px;
  }
}
</style>
