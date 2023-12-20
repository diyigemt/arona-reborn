<script setup lang="ts">
import { ComputedRef } from "vue";
import { Contact } from "@/interface";

defineOptions({
  name: "ContactProfileRole",
});
const contact = inject<ComputedRef<Contact>>("contact") as ComputedRef<Contact>;
</script>

<template>
  <ElTable :data="contact.roles">
    <ElTableColumn prop="id" label="id"></ElTableColumn>
    <ElTableColumn prop="name" label="名称"></ElTableColumn>
  </ElTable>
</template>

<style scoped lang="scss"></style>
