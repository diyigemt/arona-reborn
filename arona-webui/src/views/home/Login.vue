<template>
  <VideoBackground
    ref="video"
    :src="`https://yuuka.cdn.diyigemt.com/image/home_page/video/${pvs[pvSelector]}.webm`"
    :poster="`https://yuuka.cdn.diyigemt.com/image/home_page/image/${posters[pvSelector]}.webp`"
    :muted="false"
    :autoplay="false"
    :loop="false"
    style="width: 100%; height: 100%"
    @click="onClick"
    @ended="onEnded"
  >
    <transition name="el-fade-in">
      <div v-if="!showLogin && isCode" class="absolute-wrapper login-wrapper">
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
    <div v-if="showLogin && isCode" class="absolute-wrapper arona-login-wrapper bg-white">
      <div>
        <div class="text-2xl">您的登录认证码为:</div>
        <div class="text-4xl arona-color m-8">{{ code }}</div>
        <div>
          <ElButton :disabled="countDown > 0" @click="onClickGetCode"
            >重新获取 <span v-if="countDown">({{ countDown }})</span></ElButton
          >
        </div>
        <div v-if="respErrorMessage">{{ respErrorMessage }}</div>
        <el-text class="mt-4"
          >不会使用?
          <el-link type="primary" href="https://doc.arona.diyigemt.com/v2/manual/webui" target="_blank"
            >访问arona在线文档</el-link
          ></el-text
        >
      </div>
    </div>
    <div v-if="!isCode" class="start">touch to start</div>
    <div v-if="isSuccess" class="absolute text-xl right-16px bottom-16px color-white">UID: {{ userId }}</div>
    <div class="absolute bottom-16px w-full text-center">
      <ElLink
        href="https://beian.miit.gov.cn/"
        target="_blank"
        class="text-xl!"
        :class="{ 'color-black!': pvSelector === 0, 'color-white!': pvSelector !== 0 }"
        >桂ICP备2022008499号-2</ElLink
      >
    </div>
  </VideoBackground>
</template>

<script setup lang="ts">
import { User } from "@element-plus/icons-vue";
// @ts-ignore
import VideoBackground from "vue-responsive-video-background-player";
import { infoMessage, successMessage } from "@/utils/message";
import { UserApi } from "@/api";
import useBaseStore from "@/store/base";
import { playLoginVoice } from "@/views/home/loginVoice";
import { randomInt } from "@/utils";

defineOptions({
  name: "LoginIndex",
});
const pvs = ["pv-1", "pv-2"];
const posters = ["BG_View_Kivotos", "BG_CS_PV4_157"];
const pvSelector = randomInt(0, 2);
const showLogin = ref(false);
const code = ref("XXXXXX");
const respErrorMessage = ref("");
const loginState = ref<"wait" | "play" | "code" | "success">("wait");
const countDown = ref(0);
const isWait = computed(() => loginState.value === "wait");
const isPlay = computed(() => loginState.value === "play");
const isCode = computed(() => loginState.value === "code");
const isSuccess = computed(() => loginState.value === "success");
const router = useRouter();
const baseStore = useBaseStore();
const userId = computed(() => baseStore.userId);
let loginStateCheckHandler = 0;
let codeCountDownHandler = 0;
function onClickThirdPartLogin() {
  infoMessage("没做");
}
function startCheckLoginState() {
  loginStateCheckHandler = window.setInterval(() => {
    if (isSuccess.value) {
      return;
    }
    UserApi.fetchLoginState(code.value).then((data) => {
      switch (data.status) {
        case 0: {
          clearInterval(loginStateCheckHandler);
          code.value = "XXXXXX";
          respErrorMessage.value = "验证码已过期, 请重新获取";
          break;
        }
        case 1: {
          break;
        }
        case 2: {
          baseStore.setToken(data.token);
          UserApi.fetchUserProfile().then((user) => {
            baseStore.setUser(user);
            successMessage("登录成功");
            playLoginVoice();
            moveToNextState();
          });
          break;
        }
        default: {
          /* empty */
        }
      }
    });
  }, 3000);
}
function onClickGetCode() {
  UserApi.login().then((res) => {
    code.value = res;
    respErrorMessage.value = "验证码十分钟内有效";
    countDown.value = 59;
    startCheckLoginState();
    codeCountDownHandler = window.setInterval(() => {
      countDown.value--;
      if (countDown.value < 1) {
        clearInterval(codeCountDownHandler);
        countDown.value = 0;
      }
    }, 1000);
  });
}
function onClickAronaLogin() {
  onClickGetCode();
  showLogin.value = true;
}
const video = ref<{ player: { play(): void } }>();
function onClick() {
  if (isSuccess.value) {
    router.push("/home");
  }
  if (!isCode.value) {
    moveToNextState();
  }
  video.value?.player.play();
}
function moveToNextState() {
  switch (loginState.value) {
    case "wait": {
      loginState.value = "play";
      break;
    }
    case "play": {
      loginState.value = "code";
      break;
    }
    case "code": {
      loginState.value = "success";
      break;
    }
    case "success": {
      loginState.value = "success";
      break;
    }
    default: {
      /**/
    }
  }
}
function onEnded() {
  // @ts-ignore
  video.value?.player.$refs.video.load();
  setTimeout(() => {
    // @ts-ignore
    video.value?.player.$refs.video.play();
  }, 1000);
}
onUnmounted(() => {
  clearInterval(loginStateCheckHandler);
  clearInterval(codeCountDownHandler);
});
</script>

<style scoped lang="scss">
.start {
  position: absolute;
  pointer-events: none;
  opacity: 0.8;
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
    opacity: 0.8;
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
