<template>
  <el-menu :default-active="currentActiveMenu" :default-openeds="mapExpand" router class="main-menu custom-menu">
    <el-sub-menu index="1">
      <template #title>
        <span>群</span>
      </template>
      <el-menu-item index="/config/contact/manage">我管理的群</el-menu-item>
      <el-menu-item index="/config/contact/join">我加入的群 </el-menu-item>
    </el-sub-menu>
    <el-sub-menu index="2">
      <template #title>
        <span>策略</span>
      </template>
      <el-menu-item index="/config/policy/config">策略配置</el-menu-item>
    </el-sub-menu>
    <el-sub-menu index="3">
      <template #title>
        <span>插件偏好</span>
      </template>
      <el-menu-item index="/config/plugin/arona-preferences">Arona</el-menu-item>
    </el-sub-menu>
    <el-menu-item index="/config/user/profile">个人中心</el-menu-item>
  </el-menu>
</template>

<script setup lang="ts">
const { t } = useI18n();
const route = useRoute();
const mapExpand = ["1", "2", "3"];
const currentActiveMenu = computed(() => route.path);
</script>

<style lang="scss" scoped>
.main-menu {
  height: 100%;
  border-right: none;
  :deep(.el-sub-menu) {
    .el-menu {
      .el-menu-item {
        border-radius: 4px;
      }
    }
  }
}
</style>

<i18n>
{
  "en": {
    "side menu config": "config",
    "side menu database": "database",
    "side menu setting": "setting"
  },
  "zh-cn": {
    "side menu config": "配置文件",
    "side menu database": "数据库文件",
    "side menu setting": "设置"
  }
}
</i18n>
