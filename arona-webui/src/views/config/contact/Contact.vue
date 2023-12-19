<template>
  <div>User Contact</div>
</template>

<script setup lang="ts">
defineOptions({
  name: "UserContact",
});
const route = useRoute();
console.log(route.query);
</script>

<style lang="scss" scoped></style>
