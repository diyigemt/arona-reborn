<script setup lang="ts">
import { ComputedRef } from "vue";
import { EditableContact } from "@/interface";

defineOptions({
  name: "ContactProfileRole",
});
const contact = inject<ComputedRef<EditableContact>>("contact") as ComputedRef<EditableContact>;
</script>

<template>
  <ElTable :data="contact.roles">
    <ElTableColumn prop="id" label="id"></ElTableColumn>
    <ElTableColumn prop="name" label="名称"></ElTableColumn>
  </ElTable>
</template>

<style scoped lang="scss"></style>
