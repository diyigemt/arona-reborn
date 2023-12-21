<template>
  <div>User Policy</div>
</template>

<script setup lang="ts">
import { ContactApi } from "@/api";
import { Policy } from "@/interface";

defineOptions({
  name: "UserPolicy",
});
const route = useRoute();
const contact = route.query.id as string;
const policyId = route.query.policy as string;
console.log(route);
const policy = ref<Policy>();
onMounted(() => {
  if (contact && policyId) {
    ContactApi.fetchContactPolicy(contact, policyId).then((data) => {
      policy.value = data;
    });
  }
});
</script>

<style lang="scss" scoped></style>
