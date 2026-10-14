# 插桩产物会直接引用 Hooks 的静态方法，混淆掉方法名就对不上了。
# （正常路径上使用方的 release 根本不含这个 AAR，这条规则是给"手工在 release 里接入"
#  这种非常规用法兜底的。）
-keep class kakacat.android.helper.network.Hooks { *; }
# ContentProvider 和 BroadcastReceiver 由清单按名字实例化。
-keep class kakacat.android.helper.network.NetworkInitProvider
-keep class kakacat.android.helper.network.ProbeReceiver
