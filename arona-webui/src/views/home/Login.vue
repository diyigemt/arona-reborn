<template>
  <VideoBackground
    @click="onClick"
    ref="video"
    src="/video/pv.webm"
    poster="/image/BG_View_Kivotos.webp"
    :muted="false"
    :autoplay="false"
    :loop="false"
    @ended="onEnded"
    style="width:100%;height:100%"
  >
    <transition name="el-fade-in">
      <div v-if="!showLogin && touch" class="absolute-wrapper login-wrapper bg-white">
        <div class="login-header">登录</div>
        <el-divider />
        <el-row>
          <el-col :span="12" class="text-center">
            <el-button class="login-card" @click="onClickThirdPartLogin">
              <img src="/image/facebook.webp" alt="" class="login-icon" />
              FaceBook登录
            </el-button>
          </el-col>
          <el-col :span="12" class="text-center">
            <el-button class="login-card" @click="onClickThirdPartLogin">
              <img src="/image/twitter.webp" alt="" class="login-icon" />
              Twitter登录
            </el-button>
          </el-col>
        </el-row>
        <el-row class="mt-4">
          <el-col :span="12" class="text-center">
            <el-button class="login-card" @click="onClickThirdPartLogin">
              <img src="/image/google.webp" alt="" class="login-icon" />
              Google登录
            </el-button>
          </el-col>
          <el-col :span="12" class="text-center">
            <el-button class="login-card" @click="onClickAronaLogin">
              <img src="/image/arona.webp" alt="" class="login-icon" />
              Arona登录
            </el-button>
          </el-col>
        </el-row>
      </div>
    </transition>
    <div v-if="showLogin" class="absolute-wrapper arona-login-wrapper bg-white">
      <div>
        <div class="text-2xl">您的登录认证码为:</div>
        <div class="text-4xl arona-color m-8">{{ code }}</div>
        <el-text
          >不会使用?
          <el-link type="primary" href="https://doc.arona.diyigemt.com/" target="_blank"
            >访问arona在线文档</el-link
          ></el-text
        >
      </div>
    </div>
    <div class="start" v-if="!touch">
      touch to start
    </div>
  </VideoBackground>
</template>

<script setup lang="ts">
// @ts-ignore
import VideoBackground from "vue-responsive-video-background-player";
import { infoMessage } from "@/utils/message";

defineOptions({
  name: "LoginIndex",
});
const showLogin = ref(false);
const touch = ref(false);
const code = ref("XXXXXX");
function onClickThirdPartLogin() {
  infoMessage("没做");
}
function onClickAronaLogin() {
  showLogin.value = true;
}
const video = ref<{ player: { play(): void } }>();
function onClick() {
  touch.value = true;
  video.value?.player.play();
}
function onEnded() {
  // @ts-ignore
  video.value?.player.$refs.video.load();
  setTimeout(() => {
    // @ts-ignore
    video.value?.player.$refs.video.play();
  }, 1000);
}
</script>

<style scoped lang="scss">
.start {
  position: absolute;
  pointer-events: none;
  bottom: 8%;
  left: 50%;
  transform: translateX(-50%);
  font-size: 24px;
  width: 50%;
  text-align: center;
  text-transform: uppercase;
  background: linear-gradient(90deg, transparent 0%, white 50px, white calc(100% - 50px), transparent 100%);
  animation: kira 2s infinite;
  animation-direction: alternate;
  animation-timing-function: ease-in-out;
}
@keyframes kira {
  0% {
    opacity: 0.3;
  }
  //25% {
  //  opacity: 1;
  //}
  //75% {
  //  opacity: 0;
  //}
  100% {
    opacity: 1;
  }
}
.absolute-wrapper {
  position: absolute;
  top: 50%;
  left: 50%;
  padding: 16px;
  transform: translate(-50%, -50%);
}
.login-wrapper {
  width: 500px;
  height: 200px;
  .login-header {
    text-align: center;
    font-size: 24px;
  }
  .login-card {
    width: 80%;
    .login-icon {
      width: 1em;
      margin-right: 8px;
    }
  }
}
.arona-login-wrapper {
  width: 300px;
  height: 400px;
  text-align: center;
  display: flex;
  flex-direction: column;
  justify-content: center;
  .code {
    font-size: 36px;
  }
}
</style>
