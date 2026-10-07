# 0.6.3 首次播放、诊断入口与进度条

## 行为

“我的”仅保留「导出诊断日志」和「复制诊断日志」两个独立按钮。导出直接打开系统 TXT 保存界面；复制直接写入剪贴板，仅显示简短结果提示。两者调用同一失败/中断任务归档函数，保留原有持久记录和敏感字段脱敏机制，不再展示次数、重启保留等说明。

播放进度条移动到正在播放文件名下方，限制在左侧标题列内，右端止于播放方式列之前。时间读数保留，拖动定位行为保留。播放器主体高度为 88dp，删除原来另占一行的全宽进度区域。

## 首次播放错误

旧版在默认列表和播放器时间线均为空时，先调用空列表 setMediaItems，再用 addMediaItems 加入首条音频。Media3 1.3.1 的 MediaControllerImplBase 在这种状态下沿用 currentMediaItemIndex = -1，访问唯一音频时抛出 Index -1 out of bounds for length 1。应用队列此前已更新，因此下方栏出现，而首次上方播放失败。

修复：空队列加入首批音频时显式调用 setMediaItems(items, 0, 0L) 并 prepare；恢复非空列表和整体播放亦明确索引 0。已有非空队列仍使用追加，保留当前位置；去重和 Mutex 逻辑不变。没有修改音频数据或抽轨流程。

## 服务器密码

服务器设置中的用户名/密码用于 tx 的 Nginx Basic Auth 访问控制。手机通过 HTTPS 发送凭据，服务器验证后允许提取、同步和下载请求；它与视频平台登录及 GetAudio 账号密码独立。此次未改变服务器认证和已有凭据。

## 验证

- 46 项 Android 单元测试通过；Lint 0 错误、23 警告；调试版、正式版及验证组件构建成功。
- REDMI K80 原包升级 0.6.3 / code 17，同一签名，保留用户音频和访问配置。
- 真机 FirstPlaybackInstrumentation：0.6.2 重现完全相同的 Index -1 异常及 MediaControllerImplBase 堆栈；同一测试在 0.6.3 通过，首次选择立即播放且索引为 0。
- 真实唯一音频从音频库上方按钮正常播放；截图确认进度条位于文件名下方并止于播放方式左侧，拖动有响应。
- 我的页面显示两个简洁按钮；复制已触发，导出直接打开系统保存界面并实际生成 Download/GetAudio-diagnostics.txt（754 字节）。未逐字比对剪贴板与文件；二者复用同一导出函数。临时测试组件已卸载。

## 可重复回归

构建测试组件时传 Gradle 参数 `-PgetaudioTestRunner=com.getaudio.app.FirstPlaybackInstrumentation`，默认仍使用原批量删除入口。与目标应用使用相同签名安装后运行：

```sh
adb shell am instrument -w com.getaudio.app.test/com.getaudio.app.FirstPlaybackInstrumentation
```

组件使用负编号的临时 PCM 音频与临时默认列表，结束时清除临时文件/记录/列表并恢复原默认列表，不删除用户音频。测试会停止并清空播放队列，适合暂停播放后执行。完整多平台抽轨和服务器回归未重跑，因为本次无相关改动。
