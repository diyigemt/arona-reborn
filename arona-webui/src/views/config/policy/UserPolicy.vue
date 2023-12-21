<template>
  <div>User Policy</div>
</template>

<script setup lang="ts">
defineOptions({
  name: "UserPolicy",
});
const route = useRoute();
const contact = route.query.id;
const policyId = route.query.policy;
onMounted(() => {});
</script>

<style lang="scss" scoped></style>
