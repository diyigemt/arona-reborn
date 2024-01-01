<script setup lang="ts">
import { ComputedRef } from "vue";
import { Contact, Policy } from "@/interface";
import { ContactApi } from "@/api";
import { IConfirm, IWarningConfirm, successMessage } from "@/utils/message";

defineOptions({
  name: "ContactProfilePolicy",
});
const emit = defineEmits<{
  (e: "update"): void;
}>();
const contact = inject<ComputedRef<Contact>>("contact") as ComputedRef<Contact>;
const userId = inject("userId", "");
const router = useRouter();

function onEdit(policy: Policy) {
  router.push({ path: "/config/policy/config", query: { id: contact.value.id, pid: policy.id } });
}
function onDelete(policy: Policy) {
  IWarningConfirm("警告", `确认删除策略 ${policy.name} 吗? 操作不可逆!`).then(() => {
    ContactApi.deleteContactPolicy(contact.value.id, policy.id).then(() => {
      successMessage("成功");
      emit("update");
    });
  });
}
</script>

<template>
  <ElTable :data="contact.policies">
    <ElTableColumn prop="id" label="id"></ElTableColumn>
    <ElTableColumn prop="name" label="名称"></ElTableColumn>
    <ElTableColumn prop="effect" label="效果" width="100"></ElTableColumn>
    <ElTableColumn prop="rules" label="规则" width="120">
      <template #default="{ row }"> 共{{ (row.rules?.length ?? 0) + (row.children?.length ?? 0) }}条规则</template>
    </ElTableColumn>
    <ElTableColumn v-if="!userId" label="操作" width="180">
      <template #default="{ row }">
        <ElButton @click="onEdit(row)">编辑</ElButton>
        <ElButton type="danger" @click="onDelete(row)">删除</ElButton>
      </template>
    </ElTableColumn>
  </ElTable>
</template>

<style scoped lang="scss"></style>
