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
      </ElFormItem>
    </ElForm>
    <ElAlert
      v-if="stickers.length >= LIST_LIMIT"
      type="warning"
      :closable="false"
      class="mb-16px"
      title="只显示最新 1000 张, 用来源群过滤缩小范围"
    />

    <ElEmpty v-if="!loading && visible.length === 0" description="这一档没有表情" />
    <div v-else class="sticker-grid">
      <ElCard v-for="s in visible" :key="s.id" shadow="hover" :body-style="{ padding: '8px' }">
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
        <div class="sticker-actions">
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
import { IWarningConfirm, successMessage } from "@/utils/message";
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

const visible = computed(() => stickers.value.filter((s) => s.status === status.value));
const groupNameById = computed(() => new Map(groups.value.map((g) => [g.id, g.name])));
function countOf(value: ChatStickerStatus) {
  return stickers.value.filter((s) => s.status === value).length;
}
function groupNames(s: ChatSticker) {
  return s.groupIds.map((id) => groupNameById.value.get(id) ?? id).join(", ");
}

function reload() {
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
  IWarningConfirm("删除", "连图片文件一起删除, 所有来源群都会失去它, 是否继续?").then(() => {
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
