<script setup lang="ts">
import { ComputedRef, Ref } from "vue";
import { EditableContact, EditableContactMember } from "@/interface";
import { useTableInlineEditor } from "@/utils";
import { ContactApi } from "@/api";
import { successMessage } from "@/utils/message";

defineOptions({
  name: "ContactProfileMember",
});
const emit = defineEmits<{
  (e: "update"): void;
}>();
const contact = inject<ComputedRef<EditableContact>>("contact") as ComputedRef<EditableContact>;
const roles = computed(() => contact.value.roles);
function roleNameMapper(mem: EditableContactMember) {
  return mem.roles.map((it) => contact.value.roles.filter((r) => r.id === it)[0].name);
}
const { onConfirm, onEdit, onCancel, cache: member } = useTableInlineEditor(contact.value.members, onMemberConfirmEdit);
function onMemberConfirmEdit(): Promise<unknown> {
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
        <div v-if="row.edit">
          <ElSelect v-model="member.roles" multiple collapse-tags>
            <ElOption v-for="(e, index) in roles" :key="index" :value="e.id" :label="e.name"></ElOption>
          </ElSelect>
        </div>
        <div v-else>
          {{ roleNameMapper(row) }}
        </div>
      </template>
    </ElTableColumn>
    <ElTableColumn label="操作" width="160">
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
