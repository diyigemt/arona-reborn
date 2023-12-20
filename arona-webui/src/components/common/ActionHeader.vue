<script setup lang="ts">
defineOptions({
  name: "ActionHeader",
});
const props = defineProps<{ title: string }>();
</script>

<template>
  <div class="flex flex-justify-between mb-4">
    <div class="text-xl">
      <slot name="title">{{ props.title }}</slot>
    </div>
    <div><slot /></div>
  </div>
</template>

<style scoped lang="scss"></style>
