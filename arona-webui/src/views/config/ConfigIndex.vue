<template>
  <MainBanner />
  <el-container class="main-container">
    <el-aside width="250px">
      <MainAsideMenu />
    </el-aside>
    <el-main class="container">
      <el-scrollbar class="scrollbar-hide-horizontal">
        <div style="margin-top: 50px; padding-top: 20px">
          <router-view />
        </div>
      </el-scrollbar>
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import MainBanner from "@/components/MainBanner.vue";
import { initEventBus } from "@/utils/config/emitter";

initEventBus();
</script>

<style lang="scss" scoped>
.main-container {
  height: calc(100vh - 60px);
}
.container {
  height: 100vh;
  max-width: 100%;
  padding-right: 0;
  padding-top: 0;
  transform: translateY(-60px);
  overflow: hidden;
}
</style>
