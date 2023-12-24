<script setup lang="ts">
import { ComputedRef } from "vue";
import { EditableContact } from "@/interface";
import { useTableInlineEditor } from "@/utils";
import { ContactApi } from "@/api";
import { successMessage } from "@/utils/message";

defineOptions({
  name: "ContactProfileRole",
});
const emit = defineEmits<{
  (e: "update"): void;
}>();
const contact = inject<ComputedRef<EditableContact>>("contact") as ComputedRef<EditableContact>;
const userId = inject("userId", "");
const { onConfirm, onEdit, onCancel, cache: role } = useTableInlineEditor(contact.value.roles, onRoleConfirmEdit);
function onRoleConfirmEdit(): Promise<unknown> {
  return ContactApi.updateContactRole(contact.value.id, role.value).then(() => {
    successMessage("更新成功");
    emit("update");
  });
}
</script>

<template>
  <ElTable :data="contact.roles">
    <ElTableColumn prop="id" label="id"></ElTableColumn>
    <ElTableColumn prop="name" label="名称">
      <template #default="{ row }">
        <div v-if="row.edit">
          <ElInput v-model="role.name" maxlength="20" show-word-limit></ElInput>
        </div>
        <div v-else>
          {{ row.name }}
        </div>
      </template>
    </ElTableColumn>
    <ElTableColumn v-if="!userId" label="操作" width="160">
      <template #default="{ row }">
        <div v-if="row.edit">
          <ElButton type="primary" @click="onConfirm(row)">确认</ElButton>
          <ElButton @click="onCancel(row)">取消</ElButton>
        </div>
        <div v-else>
          <ElButton @click="onEdit(row)">编辑</ElButton>
        </div>
      </template>
    </ElTableColumn>
  </ElTable>
</template>

<style scoped lang="scss"></style>
