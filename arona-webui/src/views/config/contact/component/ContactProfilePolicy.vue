<script setup lang="ts">
import { ComputedRef } from "vue";
import { Contact } from "@/interface";

defineOptions({
  name: "ContactProfilePolicy",
});
const contact = inject<ComputedRef<Contact>>("contact") as ComputedRef<Contact>;
</script>

<template>
  <ElTable :data="contact.policies">
    <ElTableColumn prop="id" label="id"></ElTableColumn>
    <ElTableColumn prop="name" label="名称"></ElTableColumn>
    <ElTableColumn prop="effect" label="效果"></ElTableColumn>
    <ElTableColumn prop="rules" label="规则">
      <template #default="{ row }"> 共{{ row.rules.length }}条规则 </template>
    </ElTableColumn>
  </ElTable>
</template>

<style scoped lang="scss"></style>
