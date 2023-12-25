<template>
  <ElRow :gutter="16">
    <ElCol :span="4">
      <ContactList :contact="contacts" @select="onSelectContact" />
    </ElCol>
    <ElCol :span="20">
      <ContactProfile v-if="contactId" :contact-id="contactId" @update="onContactUpdate" base />
    </ElCol>
  </ElRow>
</template>

<script setup lang="ts">
import { Ref } from "vue";
import { Contact, EditableContact } from "@/interface";
import { ContactApi } from "@/api";
import ContactList from "@/views/config/contact/component/ContactList.vue";
import ContactProfile from "@/views/config/contact/component/ContactProfile.vue";
import useBaseStore from "@/store/base";

defineOptions({
  name: "UserContact",
});
const contacts = ref<Contact[]>([]);
const contactId = ref<string>() as Ref<string>;
const baseStore = useBaseStore();
provide("userId", baseStore.userId);
function onSelectContact(id: string) {
  if (!contacts.value.some((it) => it.id === id)) {
    return;
  }
  contactId.value = id;
}
function onContactUpdate(id: string) {
  fetchContacts().then(() => {
    onSelectContact(id);
  });
}
function fetchContacts() {
  return ContactApi.fetchContacts().then((res) => {
    contacts.value = res;
  });
}
onMounted(() => {
  fetchContacts();
});
</script>

<style lang="scss" scoped></style>
