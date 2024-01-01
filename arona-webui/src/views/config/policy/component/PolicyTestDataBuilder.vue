<script setup lang="ts">
import dayjs from "dayjs";
import { PolicyTestInput } from "@/views/config/policy/util";
import { ContactMember, ContactRole, PolicyResource, PolicyRuleType } from "@/interface";

defineOptions({
  name: "PolicyTestDataBuilder",
});
const props = defineProps<{
  roles: ContactRole[];
  resources: PolicyResource[];
  members: ContactMember[];
}>();
const formData = ref<PolicyTestInput>({
  Resource: {
    id: "",
  },
  Action: {
    action: "",
  },
  Subject: {
    id: "",
    roles: ["role.default"],
  },
  Environment: {
    time: dayjs().format("HH:mm:ss"),
    date: dayjs().format("YYYY-MM-DD"),
    datetime: dayjs().format("YYYY-MM-DD HH:mm:ss"),
    param1: "",
    param2: "",
  },
});
const resourcesSelect = computed(() => {
  return props.resources.map((it) => {
    return {
      label: it,
      value: it,
    };
  });
});
const rolesSelect = computed(() => {
  return props.roles.map((it) => {
    return {
      label: it.name,
      value: it.id,
    };
  });
});
const membersSelect = computed(() => {
  return props.members.map((it) => {
    return {
      label: it.name || it.id,
      value: it.id,
    };
  });
});
const tab = ref<PolicyRuleType>("Resource");
function build() {
  formData.value.Environment.datetime = `${formData.value.Environment.date} ${formData.value.Environment.time}`;
  return formData.value;
}
defineExpose({
  build,
});
</script>

<template>
  <ElTabs v-model="tab">
    <ElTabPane label="Resource" name="Resource">
      <ElForm :model="formData.Resource" label-width="80" label-position="left">
        <ElFormItem label="id">
          <ElSelect v-model="formData.Resource.id" class="w-full">
            <ElOption v-for="(e, index) in resourcesSelect" :key="index" :label="e.label" :value="e.value" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
    </ElTabPane>
    <ElTabPane label="Action" name="Action" :disabled="true">
      <h1>没做</h1>
    </ElTabPane>
    <ElTabPane label="Subject" name="Subject">
      <ElForm :model="formData.Subject" label-width="80" label-position="left">
        <ElFormItem label="id">
          <ElSelect v-model="formData.Subject.id" filterable default-first-option class="w-full">
            <ElOption v-for="(e, index) in membersSelect" :key="index" :label="e.label" :value="e.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="roles">
          <ElSelect v-model="formData.Subject.roles" multiple filterable default-first-option class="w-full">
            <ElOption v-for="(e, index) in rolesSelect" :key="index" :label="e.label" :value="e.value" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
    </ElTabPane>
    <ElTabPane label="Environment" name="Environment">
      <ElForm :model="formData.Environment" label-width="80" label-position="left">
        <ElFormItem label="date">
          <ElDatePicker v-model="formData.Environment.date" value-format="YYYY-MM-DD" class="w-full"></ElDatePicker>
        </ElFormItem>
        <ElFormItem label="time">
          <ElTimePicker v-model="formData.Environment.time" value-format="HH:mm:ss" class="w-full"></ElTimePicker>
        </ElFormItem>
        <ElFormItem label="param1">
          <ElInput v-model="formData.Environment.param1"></ElInput>
        </ElFormItem>
        <ElFormItem label="param2">
          <ElInput v-model="formData.Environment.param2"></ElInput>
        </ElFormItem>
      </ElForm>
    </ElTabPane>
  </ElTabs>
</template>

<style scoped lang="scss"></style>
