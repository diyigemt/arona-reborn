<script setup lang="ts">
import { ComputedRef } from "vue";
import { Contact, Policy } from "@/interface";

defineOptions({
  name: "ContactProfilePolicy",
});
const contact = inject<ComputedRef<Contact>>("contact") as ComputedRef<Contact>;
const router = useRouter();

function onEdit(policy: Policy) {
  router.push({ path: "/config/policy/config", query: { id: contact.value.id, pid: policy.id } });
}
</script>

<template>
  <ElTable :data="contact.policies">
    <ElTableColumn prop="id" label="id"></ElTableColumn>
    <ElTableColumn prop="name" label="名称"></ElTableColumn>
    <ElTableColumn prop="effect" label="效果"></ElTableColumn>
    <ElTableColumn prop="rules" label="规则">
      <template #default="{ row }"> 共{{ row.rules.length }}条规则</template>
    </ElTableColumn>
    <ElTableColumn label="操作" width="85">
      <template #default="{ row }">
        <ElButton @click="onEdit(row)">编辑</ElButton>
      </template>
    </ElTableColumn>
  </ElTable>
</template>

<style scoped lang="scss"></style>
