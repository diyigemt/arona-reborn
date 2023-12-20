<template>
  <div>Contact</div>
  <ContactList :contact="contacts" @select="onSelectContact" />
</template>

<script setup lang="ts">
import { Contact } from "@/interface";
import { ContactApi } from "@/api";
import ContactList from "@/views/config/contact/component/ContactList.vue";

defineOptions({
  name: "UserContact",
});
const contacts = ref<Contact[]>([]);
const contact = ref<Contact>();
function onSelectContact(id: string) {}
onMounted(() => {
  ContactApi.fetchContacts().then((data) => {
    contacts.value = data;
  });
});
</script>

<style lang="scss" scoped></style>
