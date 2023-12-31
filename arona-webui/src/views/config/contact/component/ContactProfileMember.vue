<script setup lang="ts">
import { ComputedRef, Ref } from "vue";
import { EditableContact, EditableContactMember } from "@/interface";
import { useTableInlineEditor } from "@/utils";
import { ContactApi } from "@/api";
import { successMessage, warningMessage } from "@/utils/message";

defineOptions({
  name: "ContactProfileMember",
});
const emit = defineEmits<{
  (e: "update"): void;
}>();
const contact = inject<ComputedRef<EditableContact>>("contact") as ComputedRef<EditableContact>;
const members = computed(() => contact.value.members);
const userId = inject("userId", "");
const roles = computed(() => contact.value.roles);
function roleNameMapper(mem: EditableContactMember) {
  return mem.roles.map((it) => contact.value.roles.filter((r) => r.id === it)[0].name);
}
const { onConfirm, onEdit, onCancel, cache: member } = useTableInlineEditor(members, onMemberConfirmEdit);
function onMemberConfirmEdit(data: EditableContactMember): Promise<unknown> {
  if (!data.roles.find((it) => it === "role.default")) {
    warningMessage("不能将群员角色删除");
  }
  return ContactApi.updateContactMember(contact.value.id, member.value).then(() => {
    successMessage("更新成功");
    emit("update");
  });
}
</script>

<template>
  <ElTable :data="contact.members">
    <ElTableColumn type="index" width="70" label="排序" />
    <ElTableColumn prop="name" label="昵称" sortable>
      <template #default="{ row }">
        <div v-if="row.edit">
          <ElInput v-model="member.name" maxlength="25" show-word-limit></ElInput>
        </div>
        <div v-else>
          {{ row.name }}
        </div>
      </template>
    </ElTableColumn>
    <ElTableColumn prop="roles" label="角色" width="300" sortable>
      <template #default="{ row }">
        <div v-if="row.edit && !userId">
          <ElSelect v-model="member.roles" multiple collapse-tags>
            <ElOption
              v-for="(e, index) in roles"
              :key="index"
              :value="e.id"
              :label="e.name"
              :disabled="e.id === 'role.default'"
            ></ElOption>
          </ElSelect>
        </div>
        <div v-else>
          {{ roleNameMapper(row) }}
        </div>
      </template>
    </ElTableColumn>
    <ElTableColumn label="操作" width="160">
      <template #default="{ row }">
        <div v-if="!userId || row.id === userId">
          <div v-if="row.edit">
            <ElButton type="primary" @click="onConfirm(row)">确认</ElButton>
            <ElButton @click="onCancel(row)">取消</ElButton>
          </div>
          <div v-else>
            <ElButton @click="onEdit(row)">编辑</ElButton>
          </div>
        </div>
      </template>
    </ElTableColumn>
  </ElTable>
</template>

<style scoped lang="scss"></style>
