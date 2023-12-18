<script setup lang="ts">
defineOptions({
  name: "PluginPreferenceForm",
});
const props = defineProps<{
  form: Record<string, never>;
}>();
</script>

<template>
  <div>
    <slot />
  </div>
</template>

<style scoped lang="scss"></style>
