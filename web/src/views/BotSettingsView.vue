<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
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
    bot.value = await adminApi.updateBot(bot.value.id, {
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

    <template v-else>
      <section class="status-bar" :class="{ off: !form.enabled }">
        <span class="status-bar__dot"></span>
        <div class="status-bar__copy">
          <strong>{{ form.name || '微信机器人' }}</strong>
          <span>
            {{ selectedProvider ? `${selectedProvider.name} · ${selectedProvider.model}` : '未选择模型提供方' }}
            · 上下文 {{ form.contextWindow }} 条
            · 限速 {{ form.rateLimitPerMinute }}/分钟
          </span>
        </div>
        <span class="status-bar__tag" :class="isReady ? 'ok' : 'warn'">
          {{ isReady ? '可对话' : '待配置' }}
        </span>
      </section>

      <section class="surface-card">
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
          <div class="surface-card__body">
            <div class="form-section">
              <div class="form-section__title">基础设置</div>
              <div class="form-grid">
                <el-form-item label="机器人名称" prop="name">
                  <el-input v-model="form.name" placeholder="例如 客服小助手" />
                </el-form-item>
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
                </el-form-item>
              </div>
              <div class="setting-row">
                <div>
                  <strong>启用机器人</strong>
                  <small>关闭后暂停所有自动回复，已排队的消息仍会继续投递</small>
                </div>
                <div class="setting-row__control">
                  <el-switch v-model="form.enabled" />
                </div>
              </div>
            </div>

            <div class="form-section">
              <div class="form-section__title">回复策略</div>
              <div class="setting-row">
                <div>
                  <strong>私聊自动回复</strong>
                  <small>一对一聊天收到消息后直接处理</small>
                </div>
                <div class="setting-row__control">
                  <el-switch v-model="form.replyPrivate" />
                </div>
              </div>
              <div class="setting-row">
                <div>
                  <strong>群聊自动回复</strong>
                  <small>仅在命中唤醒词时调用模型</small>
                </div>
                <div class="setting-row__control">
                  <el-switch v-model="form.replyGroup" />
                </div>
              </div>
              <el-form-item label="群聊唤醒词" prop="groupWakeWords" class="field-block">
                <el-input v-model="form.groupWakeWords" placeholder="@机器人,小助手" />
                <div class="field-hint">多个用逗号分隔；留空时群聊所有消息都会触发回复。</div>
              </el-form-item>
            </div>

            <div class="form-section">
              <div class="form-section__title">生成参数</div>
              <div class="form-grid three">
                <el-form-item label="上下文消息数">
                  <el-input-number v-model="form.contextWindow" :min="1" :max="100" controls-position="right" />
                </el-form-item>
                <el-form-item label="Temperature">
                  <el-input-number
                    v-model="form.temperature"
                    :min="0"
                    :max="2"
                    :step="0.1"
                    :precision="1"
                    controls-position="right"
                  />
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

            <div class="form-section">
              <div class="form-section__title">
                系统提示词
                <small>定义机器人身份、语气与边界</small>
              </div>
              <el-form-item prop="systemPrompt">
                <el-input
                  v-model="form.systemPrompt"
                  type="textarea"
                  :rows="5"
                  maxlength="20000"
                  show-word-limit
                  placeholder="你是一个友好、简洁、可靠的微信助手……"
                />
              </el-form-item>
            </div>
          </div>

          <div class="form-footer">
            <span class="hint">保存后立即生效，后续消息使用新配置。</span>
            <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
          </div>
        </el-form>
      </section>
    </template>
  </div>
</template>

<style scoped>
.status-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 10px 14px;
  border: 1px solid #d7e7ff;
  border-radius: var(--radius-sm);
  background: #f5f9ff;
}

.status-bar__dot {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.12);
}

.status-bar.off .status-bar__dot {
  background: var(--muted-soft);
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.14);
}

.status-bar__copy {
  min-width: 0;
  flex: 1;
}

.status-bar__copy strong {
  display: block;
  color: var(--ink);
  font-size: var(--fs-sm);
  font-weight: 650;
}

.status-bar__copy span {
  display: block;
  margin-top: 1px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.status-bar__tag {
  flex: 0 0 auto;
  padding: 2px 7px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
}

.status-bar__tag.ok {
  color: #15803d;
  background: #ecfdf3;
}

.status-bar__tag.warn {
  color: #b45309;
  background: #fff7ed;
}

.form-section:first-child {
  margin-top: 2px;
}

.field-block {
  margin-top: 10px;
}

.form-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 16px;
  border-top: 1px solid var(--line-soft);
  background: #fbfcfe;
}

@media (max-width: 680px) {
  .status-bar {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .status-bar__tag {
    order: -1;
  }

  .form-footer {
    flex-direction: column;
    align-items: stretch;
  }

  .form-footer .el-button {
    width: 100%;
  }
}
</style>

