<template>
  <VideoBackground
    ref="video"
    src="/video/pv.webm"
    poster="/image/BG_View_Kivotos.webp"
    :muted="false"
    :autoplay="false"
    :loop="false"
    style="width: 100%; height: 100%"
    @click="onClick"
    @ended="onEnded"
  >
    <transition name="el-fade-in">
      <div v-if="!showLogin && touch" class="absolute-wrapper login-wrapper">
        <div class="login-wrapper-body" style="transform: translateY(-25%)">
          <div class="login-header">Arona</div>
          <div class="tips">请选择要进行游戏的登入方法</div>
          <el-divider />
          <el-row>
            <el-col :span="12" class="text-center">
              <el-button class="login-card" @click="onClickThirdPartLogin">
                <span class="inner">
                  <img src="/image/facebook.webp" alt="" />
                  <span>FaceBook登录</span>
                </span>
              </el-button>
            </el-col>
            <el-col :span="12" class="text-center">
              <el-button class="login-card" @click="onClickThirdPartLogin">
                <span class="inner">
                  <img src="/image/twitter.webp" alt="" />
                  <span>Twitter登录</span>
                </span>
              </el-button>
            </el-col>
          </el-row>
          <el-row class="mt-4">
            <el-col :span="12" class="text-center">
              <el-button class="login-card" @click="onClickThirdPartLogin">
                <span class="inner">
                  <img src="/image/google.webp" alt="" />
                  <span>Google登录</span>
                </span>
              </el-button>
            </el-col>
            <el-col :span="12" class="text-center">
              <el-button class="login-card" @click="onClickAronaLogin">
                <span class="inner">
                  <img src="/image/arona.webp" alt="" />
                  <span>Arona登录</span>
                </span>
              </el-button>
            </el-col>
          </el-row>
          <el-row class="mt-4">
            <el-col :span="12" class="text-center">
              <el-button class="login-card" @click="onClickThirdPartLogin">
                <span class="inner">
                  <el-icon><User /></el-icon>
                  <span>以访客身份登入</span>
                </span>
              </el-button>
            </el-col>
          </el-row>
          <div class="mt-4 tips">登入我们的服务即表示您同意我们的使用条款和隐私政策</div>
        </div>
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
    <div v-if="!touch" class="start">touch to start</div>
  </VideoBackground>
</template>

<script setup lang="ts">
import { User } from "@element-plus/icons-vue";
// @ts-ignore
import VideoBackground from "vue-responsive-video-background-player";
import { infoMessage } from "@/utils/message";

defineOptions({
  name: "LoginIndex",
});
const showLogin = ref(false);
const touchCount = ref(0);
const touch = computed(() => touchCount.value > 1);
const code = ref("XXXXXX");
function onClickThirdPartLogin() {
  infoMessage("没做");
}
function onClickAronaLogin() {
  showLogin.value = true;
}
const video = ref<{ player: { play(): void } }>();
function onClick() {
  touchCount.value++;
  video.value?.player.play();
}
function onCloseLogin() {
  touchCount.value = 1;
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
  opacity: 0.6;
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
  width: 100%;
  height: 100%;
  background-color: #00000080;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  .login-wrapper-body {
    width: 500px;
    height: 200px;
  }
  .login-header {
    text-align: center;
    font-size: 24px;
    color: white;
    text-transform: uppercase;
  }
  .login-card {
    width: 80%;
    :deep(> span) {
      width: 100%;
    }
    :deep(.inner) {
      width: 100%;
      display: flex;
      flex-direction: row;
      img {
        width: 1em;
        margin-right: 8px;
        transform: translateY(1px);
      }
      span {
        text-align: center;
        flex-grow: 1;
      }
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
.tips {
  color: white;
  font-size: 12px;
}
</style>
