<script setup lang="ts">
import { Ref } from "vue";
import { Contact, ContactRole, ContactUpdateReq } from "@/interface";
import ContactProfileMember from "@/views/config/contact/component/ContactProfileMember.vue";
import ContactProfileRole from "@/views/config/contact/component/ContactProfileRole.vue";
import ContactProfilePolicy from "@/views/config/contact/component/ContactProfilePolicy.vue";
import { ContactApi } from "@/api";
import { IWarningConfirm, successMessage } from "@/utils/message";

defineOptions({
  name: "ContactProfile",
});
const props = defineProps<{
  contactId: string;
}>();
// @ts-ignore
const contact = ref<Contact>({}) as Ref<Contact>;
watch(
  () => props.contactId,
  (cur) => {
    if (cur) {
      fetchContactProfile(cur);
    }
  },
  { immediate: true },
);
const emit = defineEmits<{
  (e: "update", id: string): void;
}>();
provide("contact", contact);
const router = useRouter();
const activeTab = ref("member");
const updateBasicInfo = ref(false);
const isRoleTab = computed(() => activeTab.value === "role");
const showRoleAddDialog = ref(false);
const roleForm = ref<ContactRole>({
  id: "",
  name: "",
});
const basicInfoForm = ref<ContactUpdateReq>({ id: contact.value.id, contactName: contact.value.contactName });
function onUpdateBasicInfo() {
  ContactApi.updateContact(basicInfoForm.value).then(() => {
    successMessage("更新成功");
    fetchContactProfile(props.contactId);
    updateBasicInfo.value = false;
    emit("update", contact.value.id);
  });
}
function onCancelUpdateBasicInfo() {
  basicInfoForm.value.contactName = contact.value.contactName;
  updateBasicInfo.value = false;
}
function showAddDialog() {
  if (isRoleTab.value) {
    IWarningConfirm("警告", "目前没有做角色的删除功能, 是否继续创建?").then(() => {
      showRoleAddDialog.value = true;
    });
  } else {
    router.push({
      path: "/config/policy/config",
      query: {
        id: contact.value.id,
      },
    });
  }
}
function onConfirmRoleCreate() {
  ContactApi.createContactRole(contact.value.id, roleForm.value).then(() => {
    successMessage("创建成功");
    fetchContactProfile(props.contactId);
    emit("update", contact.value.id);
  });
}
function fetchContactProfile(id: string) {
  if (id) {
    ContactApi.fetchContact(id).then((data) => {
      contact.value = data;
    });
  }
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
      <ElDescriptionsItem label="成员数">{{ contact.members?.length }}</ElDescriptionsItem>
      <ElDescriptionsItem label="角色数">{{ contact.roles?.length }}</ElDescriptionsItem>
      <ElDescriptionsItem label="权限数">{{ contact.policies?.length }}</ElDescriptionsItem>
    </ElDescriptions>
    <ActionHeader title="不基本信息" class="mt-4 mb-2! h-32px">
      <ElButton v-if="activeTab != 'member'" @click="showAddDialog">新增</ElButton>
    </ActionHeader>
    <ElTabs v-model="activeTab">
      <ElTabPane label="成员" name="member">
        <ContactProfileMember @update="fetchContactProfile(contactId)" />
      </ElTabPane>
      <ElTabPane label="角色" name="role">
        <ContactProfileRole @update="fetchContactProfile(contactId)" />
      </ElTabPane>
      <ElTabPane label="策略" name="policy">
        <ContactProfilePolicy />
      </ElTabPane>
    </ElTabs>
    <CancelConfirmDialog v-model:show="showRoleAddDialog" title="新增角色" @confirm="onConfirmRoleCreate">
      <ElForm :model="roleForm">
        <ElFormItem label="名称">
          <ElInput v-model="roleForm.name" maxlength="20"></ElInput>
        </ElFormItem>
      </ElForm>
    </CancelConfirmDialog>
  </div>
</template>

<style scoped lang="scss"></style>
