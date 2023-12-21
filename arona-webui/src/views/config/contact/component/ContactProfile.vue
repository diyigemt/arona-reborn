<script setup lang="ts">
import { Contact, ContactUpdateReq } from "@/interface";
import ContactProfileMember from "@/views/config/contact/component/ContactProfileMember.vue";
import ContactProfileRole from "@/views/config/contact/component/ContactProfileRole.vue";
import ContactProfilePolicy from "@/views/config/contact/component/ContactProfilePolicy.vue";
import { ContactApi } from "@/api";
import { successMessage } from "@/utils/message";

defineOptions({
  name: "ContactProfile",
});
const props = defineProps<{
  contact: Contact;
}>();
const contact = computed(() => props.contact);
const emit = defineEmits<{
  (e: "update", id: string): void;
}>();
provide("contact", contact);
const activeTab = ref("member");
const updateBasicInfo = ref(false);
const basicInfoForm = ref<ContactUpdateReq>({ id: contact.value.id, contactName: contact.value.contactName });
function onUpdateBasicInfo() {
  ContactApi.updateContact(basicInfoForm.value).then(() => {
    successMessage("更新成功");
    updateBasicInfo.value = false;
    emit("update", contact.value.id);
  });
}
function onCancelUpdateBasicInfo() {
  basicInfoForm.value.contactName = contact.value.contactName;
  updateBasicInfo.value = false;
}
</script>

<template>
  <div>
    <ActionHeader title="基本信息">
      <div v-if="updateBasicInfo">
        <ElButton type="primary" @click="onUpdateBasicInfo">确认</ElButton>
        <ElButton @click="onCancelUpdateBasicInfo">取消</ElButton>
      </div>
      <div v-else>
        <ElButton @click="updateBasicInfo = true">更新</ElButton>
      </div>
    </ActionHeader>
    <ElDescriptions :column="2" border class="custom-description">
      <ElDescriptionsItem label="群名">
        <div v-if="updateBasicInfo">
          <ElInput v-model="basicInfoForm.contactName"></ElInput>
        </div>
        <div v-else>
          {{ contact.contactName }}
        </div>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="成员数">{{ contact.members.length }}</ElDescriptionsItem>
      <ElDescriptionsItem label="角色数">{{ contact.roles.length }}</ElDescriptionsItem>
      <ElDescriptionsItem label="权限数">{{ contact.policies.length }}</ElDescriptionsItem>
    </ElDescriptions>
    <ElTabs v-model="activeTab">
      <ElTabPane label="成员" name="member">
        <ContactProfileMember />
      </ElTabPane>
      <ElTabPane label="角色" name="role">
        <ContactProfileRole />
      </ElTabPane>
      <ElTabPane label="权限" name="policy">
        <ContactProfilePolicy />
      </ElTabPane>
    </ElTabs>
  </div>
</template>

<style scoped lang="scss"></style>
