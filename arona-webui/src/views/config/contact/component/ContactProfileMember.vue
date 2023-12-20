<script setup lang="ts">
import { ComputedRef } from "vue";
import { Contact, ContactMember } from "@/interface";

defineOptions({
  name: "ContactProfileMember",
});
const contact = inject<ComputedRef<Contact>>("contact") as ComputedRef<Contact>;
function roleNameMapper(member: ContactMember) {
  return member.roles.map((it) => contact.value.roles.filter((r) => r.id === it)[0].name);
}
</script>

<template>
  <ElTable :data="contact.members">
    <ElTableColumn prop="name" label="昵称"></ElTableColumn>
    <ElTableColumn prop="roles" label="角色">
      <template #default="{ row }">
        {{ roleNameMapper(row) }}
      </template>
    </ElTableColumn>
  </ElTable>
</template>

<style scoped lang="scss"></style>
