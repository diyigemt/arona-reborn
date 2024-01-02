<template>
  <el-config-provider :locale="locale">
    <router-view />
    <div ref="aronaContainer" class="arona-container" />
  </el-config-provider>
</template>

<script setup lang="ts">
// @ts-ignore
import zhCn from "element-plus/dist/locale/zh-cn.mjs";
import { AdvancedBloomFilter } from "@pixi/filter-advanced-bloom";
import { Sprite } from "pixi.js";
import SpineManager, { SpineInstanceReactConfig } from "@/utils/spine";
import { IAlert } from "@/utils/message";
import useBaseStore from "@/store/base";

const locale = zhCn;
const aronaContainer = ref<HTMLElement>();
const aronaConfig: SpineInstanceReactConfig[] = [
  {
    name: "",
    url: "",
    text: "",
    animation: "",
  },
];
const store = useBaseStore();
onMounted(() => {
  if (!store.clarity) {
    IAlert("提示", "为了改善用户体验，网站使用Microsoft Clarity的严格隐私模式收集用户交互信息");
    store.clarity = true;
  }
  // SpineManager.createSpine({
  //   el: aronaContainer.value!,
  //   name: "arona",
  //   url: "/spine/arona_spr.skel",
  //   height: 600,
  //   offsetX: 0,
  //   offsetY: 270,
  // }).then((arona) => {
  //   // arona.setAnimation(0, "Idle_01", true);
  //   const mask = Sprite.from("/image/FX_TEX_Arona_Stand.png");
  //   setTimeout(() => {
  //     console.log(arona.spine.height, mask.height);
  //     const scale = (arona.spine.height / 603.3371921592023) * 0.325;
  //     mask.scale.set(scale);
  //     mask.filters = [new AdvancedBloomFilter({ brightness: 1.5, quality: 7, pixelSize: 1 })];
  //     arona.app.stage.addChild(mask);
  //   }, 1000);
  // });
});
</script>

<style lang="scss" scoped>
.arona-container {
  position: absolute;
  top: 100px;
  left: 300px;
  height: 600px;
  width: 300px;
  background-color: black;
  display: none;
}
</style>
