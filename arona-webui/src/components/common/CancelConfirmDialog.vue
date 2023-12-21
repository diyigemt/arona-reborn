<script setup lang="ts">
defineOptions({
  name: "CancelConfirmDialog",
});
const emit = defineEmits<{
  (e: "cancel"): void;
  (e: "confirm"): void;
  (e: "update:show", cur: boolean): void;
}>();
const props = withDefaults(
  defineProps<{
    show: boolean;
    title: string;
  }>(),
  {
    show: false,
    title: "",
  },
);
const show = ref(props.show);
watch(
  () => props.show,
  (cur) => {
    show.value = cur;
  },
);
watch(
  () => show.value,
  (cur) => {
    emit("update:show", cur);
  },
);
function onCancel() {
  show.value = false;
  emit("cancel");
}
function onConfirm() {
  show.value = false;
  emit("confirm");
}
</script>

<template>
  <ElDialog v-model="show" append-to-body :title="title">
    <slot />
    <template #footer>
      <div class="text-right">
        <ElButton @click="onCancel">取消</ElButton>
        <ElButton type="primary" @click="onConfirm">确认</ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<style scoped lang="scss"></style>
