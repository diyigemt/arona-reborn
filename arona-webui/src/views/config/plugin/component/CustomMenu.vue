<script setup lang="ts">
import { Plus } from "@element-plus/icons-vue";
import { CustomMenuConfig, CustomMenuRow } from "@/interface";
import { CustomRow } from "@/views/config/plugin/component/CustomButton";

defineOptions({
  name: "CustomMenu",
});
// eslint-disable-next-line no-undef
const props = withDefaults(defineProps<{ data: CustomMenuConfig }>(), {
  data: () => ({
    rows: [
      {
        buttons: [
          { label: "官服总力档线", data: "/总力档线 官服", enter: true },
          { label: "B服总力档线", data: "/总力档线 B服", enter: true },
        ],
      },
      {
        buttons: [
          { label: "日服总力档线", data: "/总力档线 日服", enter: true },
          { label: "日服大决战档线", data: "/大决战档线 日服", enter: true },
        ],
      },
      {
        buttons: [
          { label: "国际服未来视", data: "/攻略 未来视", enter: true },
          { label: "国服日程", data: "/攻略 国服日程", enter: true },
        ],
      },
      {
        buttons: [
          { label: "日服总力", data: "/攻略 日服总力", enter: true },
          { label: "国际服总力", data: "/攻略 国际服总力", enter: true },
        ],
      },
      {
        buttons: [
          { label: "塔罗牌", data: "/塔罗牌", enter: true },
          { label: "十连", data: "/十连", enter: true },
        ],
      },
    ],
  }),
});
const menu = ref(props.data);
const emit = defineEmits<{
  (e: "update:data", data: CustomMenuConfig): void;
}>();
function onRowAdd() {
  menu.value.rows.push({
    buttons: [],
  });
}
function onRowUpdate(row: CustomMenuRow, index: number) {
  if (row.buttons.length === 0) {
    menu.value.rows.splice(index, 1);
  } else {
    menu.value.rows.splice(index, 1, row);
  }
}
function update() {
  emit("update:data", {
    rows: menu.value.rows,
  });
}
</script>

<template>
  <div>
    <CustomRow
      v-for="(e, index) in menu.rows"
      :key="e"
      :row="e"
      class="mb-8px"
      @update:row="onRowUpdate($event, index)"
    />
    <ElRow v-if="menu.rows.length < 5" :gutter="16">
      <ElCol class="flex-1!">
        <ElButton :icon="Plus" plain class="w-100% mb-8px" @click="onRowAdd" />
      </ElCol>
      <ElCol class="max-w-60px! flex-1!" style="opacity: 0">
        <ElButton :icon="Plus" />
      </ElCol>
    </ElRow>
  </div>
</template>

<style scoped lang="scss"></style>
