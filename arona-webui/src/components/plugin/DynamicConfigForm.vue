<script setup lang="ts">
import { inject, reactive, ref, watch } from "vue";
import type { Ref } from "vue";
import type {
  ConfigFieldSchema,
  ConfigFieldType,
  FieldError,
} from "@/interface/pluginSchema";
import { resolveWidget } from "@/components/plugin/widgetRegistry";

const props = defineProps<{
  schema: ConfigFieldSchema[];
  // 当前层对应的 reactive 对象 (顶层对象或子对象的引用), 子组件直接 mutate.
  modelValue: Record<string, unknown>;
  pId: string;
  pKey: string;
  /** 递归路径前缀, 顶层为 ""; 用于 widgetRegistry 查找和 fieldErrors 匹配. */
  fieldPath?: string;
}>();

defineOptions({
  name: "DynamicConfigForm",
});

const fieldErrors = inject<Ref<FieldError[]>>("fieldErrors", ref([]));

function pathOf(field: ConfigFieldSchema): string {
  return props.fieldPath ? `${props.fieldPath}.${field.key}` : field.key;
}

function errorOf(path: string): string | undefined {
  return fieldErrors.value.find((e) => e.path === path)?.message;
}

function customComponent(field: ConfigFieldSchema) {
  return resolveWidget(props.pId, props.pKey, pathOf(field));
}

function ensureNestedObject(field: ConfigFieldSchema): Record<string, unknown> {
  // object 字段递归时需要传一个 reactive 子对象引用; 若后端默认值缺失, 用 {} 初始化保证 v-model 能写入.
  let nested = props.modelValue[field.key] as Record<string, unknown> | undefined;
  if (nested == null || typeof nested !== "object") {
    nested = {};
    props.modelValue[field.key] = nested;
  }
  return nested;
}

function readBool(field: ConfigFieldSchema): boolean {
  const v = props.modelValue[field.key];
  return typeof v === "boolean" ? v : false;
}
function readNumber(field: ConfigFieldSchema): number {
  const v = props.modelValue[field.key];
  return typeof v === "number" ? v : 0;
}
function readString(field: ConfigFieldSchema): string {
  const v = props.modelValue[field.key];
  return typeof v === "string" ? v : "";
}
function setValue(field: ConfigFieldSchema, val: unknown): void {
  props.modelValue[field.key] = val;
}

// 复杂类型 (array/map/polymorphic/unknown) 降级 textarea 编辑 JSON 字面量.
// 用本地 draft 缓存输入, 仅在 blur 时尝试 parse, 解析失败保留 draft 让用户继续编辑;
// 直接绑定 stringify 派生值会让半成品 JSON 在每次 keystroke 后回弹, 无法实际编辑.
const fallbackDrafts = reactive<Record<string, string>>({});
const fallbackErrors = reactive<Record<string, string>>({});

function fallbackText(field: ConfigFieldSchema): string {
  const path = pathOf(field);
  if (path in fallbackDrafts) return fallbackDrafts[path];
  return JSON.stringify(props.modelValue[field.key] ?? null, null, 2);
}

function onFallbackInput(field: ConfigFieldSchema, raw: string): void {
  const path = pathOf(field);
  fallbackDrafts[path] = raw;
  // 用户开始修正后立即清掉旧的解析错误, 避免错误文案在新一次 blur 之前一直挂着.
  delete fallbackErrors[path];
}

function onFallbackBlur(field: ConfigFieldSchema): void {
  const path = pathOf(field);
  const raw = fallbackDrafts[path];
  if (raw === undefined) return;
  try {
    setValue(field, JSON.parse(raw));
    delete fallbackDrafts[path];
    delete fallbackErrors[path];
  } catch (e) {
    fallbackErrors[path] = e instanceof Error ? e.message : "JSON 解析失败";
  }
}

// 外部 modelValue 变更 (例如作用域切换重新 emit update:form) 时清掉本地 draft, 否则旧编辑会泄漏到新对象.
watch(
  () => props.modelValue,
  () => {
    Object.keys(fallbackDrafts).forEach((k) => delete fallbackDrafts[k]);
    Object.keys(fallbackErrors).forEach((k) => delete fallbackErrors[k]);
  },
);

function fallbackError(field: ConfigFieldSchema): string | undefined {
  return fallbackErrors[pathOf(field)] ?? errorOf(pathOf(field));
}

const FALLBACK_TYPES: ReadonlySet<ConfigFieldType> = new Set([
  "array",
  "map",
  "polymorphic",
  "unknown",
]);

function isFallback(field: ConfigFieldSchema): boolean {
  return FALLBACK_TYPES.has(field.type);
}
</script>

<template>
  <template v-for="field in schema" :key="pathOf(field)">
    <component
      :is="customComponent(field)"
      v-if="customComponent(field)"
      :field="field"
      :model-value="props.modelValue[field.key]"
      :p-id="pId"
      :p-key="pKey"
      :field-path="pathOf(field)"
      @update:model-value="(v: unknown) => setValue(field, v)"
    />

    <fieldset
      v-else-if="field.type === 'object'"
      class="dyn-config-fieldset"
    >
      <legend>{{ field.label }}</legend>
      <DynamicConfigForm
        :schema="field.fields ?? []"
        :model-value="ensureNestedObject(field)"
        :p-id="pId"
        :p-key="pKey"
        :field-path="pathOf(field)"
      />
    </fieldset>

    <ElFormItem
      v-else
      :label="field.label"
      :prop="pathOf(field)"
      :error="isFallback(field) ? fallbackError(field) : errorOf(pathOf(field))"
    >
      <ElSwitch
        v-if="field.type === 'boolean'"
        :model-value="readBool(field)"
        @update:model-value="(v) => setValue(field, v)"
      />
      <ElInputNumber
        v-else-if="field.type === 'integer'"
        :model-value="readNumber(field)"
        :precision="0"
        :step="1"
        @update:model-value="(v) => setValue(field, v)"
      />
      <ElInputNumber
        v-else-if="field.type === 'number'"
        :model-value="readNumber(field)"
        @update:model-value="(v) => setValue(field, v)"
      />
      <ElSelect
        v-else-if="field.type === 'enum'"
        :model-value="readString(field)"
        @update:model-value="(v) => setValue(field, v)"
      >
        <ElOption
          v-for="opt in field.enumOptions ?? []"
          :key="opt.value"
          :value="opt.value"
          :label="opt.label"
        />
      </ElSelect>
      <ElInput
        v-else-if="field.type === 'string'"
        :model-value="readString(field)"
        :placeholder="field.placeholder"
        @update:model-value="(v) => setValue(field, v)"
      />
      <ElInput
        v-else-if="isFallback(field)"
        type="textarea"
        :rows="4"
        :model-value="fallbackText(field)"
        @update:model-value="(v) => onFallbackInput(field, v)"
        @blur="onFallbackBlur(field)"
      />
      <ElInput v-else disabled :model-value="`不支持的字段类型: ${field.type}`" />
      <div v-if="field.description" class="dyn-config-desc">{{ field.description }}</div>
    </ElFormItem>
  </template>
</template>

<style scoped lang="scss">
.dyn-config-fieldset {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 12px 16px;
  margin-bottom: 16px;

  legend {
    padding: 0 6px;
    font-size: 14px;
    color: var(--el-text-color-regular);
  }
}
.dyn-config-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
</style>
