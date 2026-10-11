package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"time"

	"github.com/metacubex/mihomo/adapter"

	"stelliberty_core/overrides"

	"go.yaml.in/yaml/v3"
)

// 与运行时测速同一目标：应用各处的 delay 测试都没传自定义 URL，沿用内核 REST 的默认值。
const previewDelayTestURL = "http://www.gstatic.com/generate_204"

type previewDelayResult struct {
	Delay int `json:"delay"`
}

// 预览态节点测速与运行时 /proxies/{name}/delay 同口径：ParseProxy 组装真实出口后 URLTest，
// 完整走节点的传输层（含 hysteria2 / tuic 这类 UDP 入口）与 TLS，数值和可用性结论都能与启动后对齐。
// 固有差异只剩一点：内核未运行，订阅 dns 段不生效、节点域名走系统解析器。
//
// unified-delay 是内核启动时写入 adapter.UnifiedDelay 的全局开关：开启后 URLTest 在同一连接上
// 发第二个请求、从第二请求起计时，去掉 TCP/TLS/协议握手开销。预览态没有内核实例，开关停在默认
// false，会把握手全部计入，数值比运行态系统性偏高——这里按订阅配置临时设置，测完还原。
func applyUnifiedDelayFromConfig(config []byte) (restore func()) {
	var head struct {
		UnifiedDelay bool `yaml:"unified-delay"`
	}
	// 只关心顶层这一个布尔；其余字段解析失败与否都不影响测速。
	_ = yaml.Unmarshal(config, &head)
	prev := adapter.UnifiedDelay.Swap(head.UnifiedDelay)
	return func() { adapter.UnifiedDelay.Store(prev) }
}
//
//export stellibertyProxyNodeDelay
func stellibertyProxyNodeDelay(cWorkDir, cTransform, cKey, cNode *C.char, cTimeout C.int) *C.char {
	return guardString(func() string {
		workDir := C.GoString(cWorkDir)
		out, err := readTransformedConfig(workDir, C.GoString(cTransform), C.GoString(cKey))
		if err != nil {
			return "error: " + err.Error()
		}
		mapping, err := overrides.FindNodeMapping(out, C.GoString(cNode), proxyProviderCacheContents(workDir, out))
		if err != nil {
			return "error: " + err.Error()
		}
		proxy, err := adapter.ParseProxy(mapping)
		if err != nil {
			return "error: parse proxy: " + err.Error()
		}
		timeout := time.Duration(C.int(cTimeout)) * time.Millisecond
		if timeout <= 0 {
			timeout = 5 * time.Second
		}
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		defer cancel()
		restore := applyUnifiedDelayFromConfig(out)
		defer restore()
		delay, err := proxy.URLTest(ctx, previewDelayTestURL, nil)
		if err != nil || delay == 0 {
			return "error: node unreachable"
		}
		data, err := json.Marshal(previewDelayResult{Delay: int(delay)})
		if err != nil {
			return "error: " + err.Error()
		}
		return string(data)
	})
}
