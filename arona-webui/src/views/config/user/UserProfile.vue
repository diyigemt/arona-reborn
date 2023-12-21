<script setup lang="ts">
import { Ref } from "vue";
import { User } from "@/interface";
import { UserApi } from "@/api";
import { successMessage } from "@/utils/message";

defineOptions({
  name: "UserProfile",
});
const updateBasicInfo = ref(false);
// @ts-ignore
const user = ref<User>({}) as Ref<User>;
const basicInfoForm = ref<User>() as Ref<User>;
function onDoUpdateBasicInfo() {
  UserApi.updateUserProfile(basicInfoForm.value).then(() => {
    updateBasicInfo.value = false;
    successMessage("更新成功");
    fetchUserProfile();
  });
}
function onUpdateBasicInfo() {
  basicInfoForm.value = user.value;
  updateBasicInfo.value = true;
}
function fetchUserProfile() {
  UserApi.fetchUserProfile().then((data) => {
    user.value = data;
  });
}
onMounted(() => {
  fetchUserProfile();
});
</script>

<template>
  <ActionHeader title="基本信息">
    <div v-if="updateBasicInfo">
      <ElButton type="primary" @click="onDoUpdateBasicInfo">确认</ElButton>
      <ElButton @click="updateBasicInfo = false">取消</ElButton>
    </div>
    <div v-else>
      <ElButton @click="onUpdateBasicInfo">更新</ElButton>
    </div>
  </ActionHeader>
  <ElDescriptions :column="2" border class="custom-description">
    <ElDescriptionsItem label="id">{{ user.id }}</ElDescriptionsItem>
    <ElDescriptionsItem label="用户名">
      <div v-if="updateBasicInfo">
        <ElInput v-model="basicInfoForm.username"></ElInput>
      </div>
      <div v-else>
        {{ user.username }}
      </div>
    </ElDescriptionsItem>
  </ElDescriptions>
</template>

<style scoped lang="scss"></style>
