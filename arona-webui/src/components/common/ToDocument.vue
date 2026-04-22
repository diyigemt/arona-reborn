<script setup lang="ts">
import { QuestionFilled } from "@element-plus/icons-vue";

defineOptions({
  name: "ToDocument",
});
const props = withDefaults(
  defineProps<{
    path: string;
  }>(),
  {
    path: "",
  },
);
const basePath = "https://doc.arona.diyigemt.com/v2/webui";
function onClick() {
  // 第三参数中显式声明 noopener noreferrer; 部分浏览器仍可能透传 opener, 显式置 null 兜底.
  const target = window.open(`${basePath}${props.path}`, "_blank", "noopener,noreferrer");
  if (target) {
    target.opener = null;
  }
}
</script>

<template>
  <el-tooltip effect="dark" content="看不懂? 点击去文档看看" placement="top">
    <ElIcon class="cursor-pointer ml-4" color="var(--el-color-primary-light-3)" @click="onClick">
      <QuestionFilled />
    </ElIcon>
  </el-tooltip>
</template>

<style scoped lang="scss"></style>
