<template>
  <ElEmpty v-if="!baseStore.user.superAdmin" description="只有主配置 superAdminUid 里的用户能审核表情" />
  <template v-else>
    <ElForm inline>
      <ElFormItem label="来源群">
        <ElSelect v-model="groupId" placeholder="全部" clearable class="w-280px!" @change="reload">
          <ElOption v-for="g in groups" :key="g.id" :label="g.name" :value="g.id" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem>
        <ElRadioGroup v-model="status">
          <ElRadioButton v-for="s in STATUSES" :key="s.value" :value="s.value">
            {{ s.label }} {{ countOf(s.value) }}
          </ElRadioButton>
        </ElRadioGroup>
      </ElFormItem>
      <ElFormItem>
        <ElButton :loading="loading" @click="reload">刷新</ElButton>
        <ElButton :type="batch ? 'primary' : 'default'" @click="batch = !batch">批量</ElButton>
      </ElFormItem>
    </ElForm>
    <div v-if="batch" class="batch-bar">
      <span>已选 {{ selected.size }} / {{ visible.length }}</span>
      <ElButton size="small" :disabled="busy" @click="selectAll">全选本档</ElButton>
      <ElButton size="small" :disabled="selected.size === 0 || busy" @click="selected.clear()">清空</ElButton>
      <ElButton
        size="small"
        type="success"
        :disabled="selected.size === 0"
        :loading="busy"
        @click="batchStatus('ready')"
        >通过</ElButton
      >
      <ElButton size="small" :disabled="selected.size === 0" :loading="busy" @click="batchStatus('hidden')"
        >隐藏</ElButton
      >
      <ElButton
        size="small"
        type="warning"
        :disabled="selected.size === 0"
        :loading="busy"
        @click="batchStatus('rejected')"
        >拒绝</ElButton
      >
      <ElButton size="small" type="danger" :disabled="selected.size === 0" :loading="busy" @click="batchRemove"
        >删除</ElButton
      >
    </div>
    <ElAlert
      v-if="stickers.length >= LIST_LIMIT"
      type="warning"
      :closable="false"
      class="mb-16px"
      title="只显示最新 1000 张, 用来源群过滤缩小范围"
    />

    <ElEmpty v-if="!loading && visible.length === 0" description="这一档没有表情" />
    <div v-else class="sticker-grid">
      <ElCard
        v-for="s in visible"
        :key="s.id"
        shadow="hover"
        :body-style="{ padding: '8px' }"
        :class="{ 'sticker-selected': selected.has(s.id), 'sticker-selectable': batch }"
        @click="toggle(s)"
      >
        <!-- 公开静态链接, 不把后台地址当 referrer 带出去 -->
        <img v-if="s.url" :src="s.url" class="sticker-img" loading="lazy" referrerpolicy="no-referrer" alt="" />
        <div v-else class="sticker-img sticker-missing">无图</div>
        <div class="mt-8px">
          <ElTag v-for="t in s.tags" :key="t" size="small" class="mr-4px mb-4px">{{ t }}</ElTag>
        </div>
        <div class="sticker-summary" :title="s.summary">{{ s.summary || "(无描述)" }}</div>
        <div class="sticker-meta">
          <ElTag v-if="s.nsfwRisk === 'high'" type="danger" size="small">nsfw 高</ElTag>
          <ElTag v-else-if="s.nsfwRisk === 'mid'" type="warning" size="small">nsfw 中</ElTag>
          <span>用过 {{ s.useCount }} 次</span>
          <span v-if="s.width && s.height">{{ s.width }}×{{ s.height }}</span>
        </div>
        <div class="sticker-groups" :title="groupNames(s)">来源: {{ groupNames(s) }}</div>
        <div v-if="!batch" class="sticker-actions">
          <ElButton v-if="s.status !== 'ready' && s.hasFile" size="small" type="success" @click="setStatus(s, 'ready')"
            >通过</ElButton
          >
          <ElButton v-if="s.status !== 'hidden'" size="small" @click="setStatus(s, 'hidden')">隐藏</ElButton>
          <ElButton v-if="s.status !== 'rejected'" size="small" type="warning" @click="setStatus(s, 'rejected')"
            >拒绝</ElButton
          >
          <ElButton size="small" @click="openEdit(s)">编辑</ElButton>
          <ElButton size="small" type="danger" @click="remove(s)">删除</ElButton>
        </div>
      </ElCard>
    </div>

    <ElDialog v-model="showEdit" title="编辑表情" width="480px" :close-on-click-modal="false" append-to-body>
      <ElForm :model="editForm" label-width="60" label-position="left">
        <ElFormItem label="标签">
          <ElSelect
            v-model="editForm.tags"
            multiple
            filterable
            allow-create
            default-first-option
            :multiple-limit="8"
            placeholder="输入后回车, 最多 8 个"
          >
            <ElOption v-for="t in editForm.tags" :key="t" :label="t" :value="t" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="描述">
          <ElInput v-model="editForm.summary" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="showEdit = false">取消</ElButton>
        <ElButton type="primary" @click="saveEdit">保存</ElButton>
      </template>
    </ElDialog>
  </template>
</template>

<script setup lang="ts">
import { ChatbotApi } from "@/api";
import type { ChatSticker, ChatStickerGroup, ChatStickerStatus } from "@/interface";
import { IWarningConfirm, successMessage, warningMessage } from "@/utils/message";
import useBaseStore from "@/store/base";

defineOptions({
  name: "ChatbotStickerLibrary",
});

const STATUSES: { value: ChatStickerStatus; label: string }[] = [
  { value: "pending", label: "待审核" },
  { value: "ready", label: "可用" },
  { value: "hidden", label: "已隐藏" },
  { value: "rejected", label: "已拒绝" },
];
// 与后端 StickerStore.LIST_LIMIT 一致
const LIST_LIMIT = 1000;

const baseStore = useBaseStore();
const groups = ref<ChatStickerGroup[]>([]);
// "" = 全部
const groupId = ref("");
const status = ref<ChatStickerStatus>("pending");
const stickers = ref<ChatSticker[]>([]);
const loading = ref(false);
const showEdit = ref(false);
const editForm = ref<{ id: string; tags: string[]; summary: string }>({ id: "", tags: [], summary: "" });

const batch = ref(false);
// 一次只跑一个批量操作: 防双击/中途改选被结尾的 clear 吞掉
const busy = ref(false);
const selected = ref(new Set<string>());
const visible = computed(() => stickers.value.filter((s) => s.status === status.value));
// 换档/切群/刷新/退出批量后, 选中的卡片可能已不在视野里, 一律清空重选
watch([status, groupId, batch], () => selected.value.clear());
const groupNameById = computed(() => new Map(groups.value.map((g) => [g.id, g.name])));
function countOf(value: ChatStickerStatus) {
  return stickers.value.filter((s) => s.status === value).length;
}
function groupNames(s: ChatSticker) {
  return s.groupIds.map((id) => groupNameById.value.get(id) ?? id).join(", ");
}

function toggle(s: ChatSticker) {
  if (!batch.value || busy.value) return;
  if (!selected.value.delete(s.id)) selected.value.add(s.id);
}
function selectAll() {
  if (busy.value) return;
  visible.value.forEach((s) => selected.value.add(s.id));
}
const selectedList = computed(() => visible.value.filter((s) => selected.value.has(s.id)));
// 批量就是循环单个接口 (每个是一次很轻的 Mongo 更新), 不值得为此加后端批量端点; 20 个一批串行, 全选 1000 张也不会瞬间打满后端
async function inChunks(items: ChatSticker[], run: (s: ChatSticker) => Promise<unknown>) {
  const results: PromiseSettledResult<unknown>[] = [];
  for (let i = 0; i < items.length; i += 20) {
    results.push(...(await Promise.allSettled(items.slice(i, i + 20).map(run))));
  }
  return results;
}
async function batchStatus(next: ChatStickerStatus) {
  if (busy.value) return;
  const pending = selectedList.value.filter((s) => s.status !== next);
  const targets = next === "ready" ? pending.filter((s) => s.hasFile) : pending;
  const skipped = pending.length - targets.length;
  busy.value = true;
  try {
    const results = await inChunks(targets, (s) =>
      ChatbotApi.updateSticker({ id: s.id, status: next }).then(() => (s.status = next)),
    );
    const failed = results.filter((r) => r.status === "rejected").length;
    selected.value.clear();
    report(targets.length - failed, failed, skipped ? `, 跳过无图 ${skipped} 张` : "");
  } finally {
    busy.value = false;
  }
}
function batchRemove() {
  if (busy.value) return;
  IWarningConfirm(
    "删除",
    `连图片文件一起删除 ${selected.value.size} 张, 所有来源群都会失去它们, 相同的图以后也不会再入库, 是否继续?`,
  ).then(async () => {
    const targets = selectedList.value;
    busy.value = true;
    try {
      const results = await inChunks(targets, (s) => ChatbotApi.deleteSticker(s.id));
      const removed = new Set(targets.filter((_, i) => results[i].status === "fulfilled").map((s) => s.id));
      stickers.value = stickers.value.filter((s) => !removed.has(s.id));
      selected.value.clear();
      report(removed.size, targets.length - removed.size, "");
    } finally {
      busy.value = false;
    }
  });
}
function report(ok: number, failed: number, extra: string) {
  if (failed) warningMessage(`成功 ${ok} 张, 失败 ${failed} 张${extra}`);
  else successMessage(`已处理 ${ok} 张${extra}`);
}
function reload() {
  selected.value.clear();
  loading.value = true;
  ChatbotApi.fetchStickers(groupId.value || undefined)
    .then((data) => {
      stickers.value = data.stickers;
      groups.value = data.groups;
    })
    .finally(() => (loading.value = false));
}
function setStatus(s: ChatSticker, next: ChatStickerStatus) {
  ChatbotApi.updateSticker({ id: s.id, status: next }).then(() => {
    s.status = next;
    successMessage("已更新");
  });
}
function openEdit(s: ChatSticker) {
  editForm.value = { id: s.id, tags: [...s.tags], summary: s.summary };
  showEdit.value = true;
}
function saveEdit() {
  ChatbotApi.updateSticker(editForm.value).then(() => {
    showEdit.value = false;
    successMessage("已保存");
    reload();
  });
}
function remove(s: ChatSticker) {
  IWarningConfirm("删除", "连图片文件一起删除, 所有来源群都会失去它, 相同的图以后也不会再入库, 是否继续?").then(() => {
    ChatbotApi.deleteSticker(s.id).then(() => {
      stickers.value = stickers.value.filter((it) => it.id !== s.id);
      successMessage("已删除");
    });
  });
}

onMounted(() => {
  if (baseStore.user.superAdmin) reload();
});
</script>

<style lang="scss" scoped>
.batch-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  .el-button + .el-button {
    margin-left: 0;
  }
}
.sticker-selectable {
  cursor: pointer;
  user-select: none;
}
.sticker-selected {
  outline: 2px solid var(--el-color-primary);
}
.sticker-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}
.sticker-img {
  display: block;
  width: 100%;
  height: 160px;
  object-fit: contain;
  background: var(--el-fill-color-light);
}
.sticker-missing {
  display: grid;
  place-items: center;
  color: var(--el-text-color-secondary);
}
.sticker-summary {
  margin: 4px 0;
  font-size: 13px;
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sticker-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.sticker-groups {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sticker-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 8px;
  .el-button + .el-button {
    margin-left: 0;
  }
}
</style>
