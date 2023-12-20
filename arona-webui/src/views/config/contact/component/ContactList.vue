<script setup lang="ts">
import { Contact } from "@/interface";

defineOptions({
  name: "ContactList",
});
const props = defineProps<{ contact: Contact[] }>();
const select = ref("");
const emits = defineEmits<{
  (e: "select", id: string): void;
}>();
function onClick(e: MouseEvent) {
  if (Reflect.get(e.target || {}, "getAttribute")) {
    const t = e.target as unknown;
    // @ts-ignore
    select.value = t.getAttribute("id") || select.value;
  }
}
watch(
  () => select.value,
  (cur) => {
    if (cur) {
      emits("select", cur);
    }
  },
);
</script>

<template>
  <ElScrollbar height="500px" class="custom-menu el-sub-menu" @click="onClick">
    <div
      v-for="(e, index) in props.contact"
      :id="e.id"
      :key="index"
      class="el-menu-item border-rd-5px truncate block"
      :class="{ 'is-active': select === e.id }"
    >
      {{ e.contactName || e.id }}({{ e.id }})
    </div>
  </ElScrollbar>
</template>

<style scoped lang="scss">
.el-menu-item.is-active {
  background-color: var(--el-color-primary-light-8);
}
</style>
