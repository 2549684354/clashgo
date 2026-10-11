package overrides

import (
	"fmt"

	"go.yaml.in/yaml/v3"
)

// 代理页在内核未运行时的静态预览：与链式代理页面同一条「套完覆写的配置」链路解析节点与代理组，
// provider 成员由调用方从缓存文件读出后传入——overrides 不感知磁盘布局。

type PreviewNode struct {
	Name   string `json:"name"`
	Type   string `json:"type"`
	Server string `json:"server"`
	Port   string `json:"port"`
}

type PreviewGroup struct {
	Name string   `json:"name"`
	Type string   `json:"type"`
	Icon string   `json:"icon"`
	All  []string `json:"all"`
}

type ProxyPreview struct {
	Nodes  []PreviewNode  `json:"nodes"`
	Groups []PreviewGroup `json:"groups"`
}

// ProxyPreviewOf 提取节点与代理组；providerContents 是 proxy-provider 名到缓存内容的映射。
// 组成员静态补不齐的（provider 缓存缺失）保持已解析的部分，内核启动后仍会给出完整成员。
func ProxyPreviewOf(config []byte, providerContents map[string][]byte) (*ProxyPreview, error) {
	result := &ProxyPreview{Nodes: []PreviewNode{}, Groups: []PreviewGroup{}}
	_, root, err := parseRoot(config)
	if err != nil {
		return result, nil
	}
	budget := maxExpandedNodes
	root, err = expand(root, &budget)
	if err != nil {
		return nil, err
	}

	providerMembers := make(map[string][]PreviewNode, len(providerContents))
	providerNodeSeen := make(map[string]bool)
	var providerNodes []PreviewNode
	for name, content := range providerContents {
		nodes := previewProviderNodes(content)
		providerMembers[name] = nodes
		for _, node := range nodes {
			if providerNodeSeen[node.Name] {
				continue
			}
			providerNodeSeen[node.Name] = true
			providerNodes = append(providerNodes, node)
		}
	}

	var inlineNames []string
	inlineSeen := make(map[string]bool)
	for _, proxy := range mappingItems(root, "proxies") {
		name := scalar(proxy, "name")
		if blank(name) || inlineSeen[name] {
			continue
		}
		inlineSeen[name] = true
		inlineNames = append(inlineNames, name)
		result.Nodes = append(result.Nodes, PreviewNode{
			Name:   name,
			Type:   scalar(proxy, "type"),
			Server: scalar(proxy, "server"),
			Port:   scalar(proxy, "port"),
		})
	}
	result.Nodes = append(result.Nodes, providerNodes...)

	for _, group := range mappingItems(root, "proxy-groups") {
		name := scalar(group, "name")
		if blank(name) {
			continue
		}
		members := scalarItems(group, "proxies")
		includeProxies := isTruthy(scalar(group, "include-all")) || isTruthy(scalar(group, "include-all-proxies"))
		includeProviders := isTruthy(scalar(group, "include-all")) || isTruthy(scalar(group, "include-all-providers"))
		if includeProxies {
			members = append(members, inlineNames...)
		}
		for _, providerName := range scalarItems(group, "use") {
			for _, node := range providerMembers[providerName] {
				members = append(members, node.Name)
			}
		}
		if includeProviders {
			for _, nodes := range providerMembers {
				for _, node := range nodes {
					members = append(members, node.Name)
				}
			}
		}
		result.Groups = append(result.Groups, PreviewGroup{
			Name: name,
			Type: scalar(group, "type"),
			Icon: scalar(group, "icon"),
			All:  dedupe(members),
		})
	}
	return result, nil
}

// FindNodeMapping 按名字取节点的完整映射，供预览测速在应用进程内直接组装内核出口。
// 查找口径与 ProxyPreviewOf 一致：inline proxies 优先，provider 成员按缓存内容兜底，同名取先见者。
func FindNodeMapping(config []byte, name string, providerContents map[string][]byte) (map[string]any, error) {
	_, root, err := parseRoot(config)
	if err != nil {
		return nil, err
	}
	budget := maxExpandedNodes
	root, err = expand(root, &budget)
	if err != nil {
		return nil, err
	}
	if node := nodeMappingByName(mappingItems(root, "proxies"), name); node != nil {
		return nodeMappingToMap(node)
	}
	for _, content := range providerContents {
		providerNodes, err := providerProxyMappings(content)
		if err != nil {
			continue
		}
		if node := nodeMappingByName(providerNodes, name); node != nil {
			return nodeMappingToMap(node)
		}
	}
	return nil, fmt.Errorf("node %q not found", name)
}

func nodeMappingByName(mappings []*yaml.Node, name string) *yaml.Node {
	for _, proxy := range mappings {
		if scalar(proxy, "name") == name {
			return proxy
		}
	}
	return nil
}

// provider 文件就是一份只带 proxies 段的 clash 配置；解析失败返回错误，由调用方决定是否跳过。
func providerProxyMappings(content []byte) ([]*yaml.Node, error) {
	_, root, err := parseRoot(content)
	if err != nil {
		return nil, err
	}
	budget := maxExpandedNodes
	root, err = expand(root, &budget)
	if err != nil {
		return nil, err
	}
	return mappingItems(root, "proxies"), nil
}

// yaml 节点转回通用映射，供 adapter.ParseProxy 解码；别名已在 expand 阶段展开。
func nodeMappingToMap(node *yaml.Node) (map[string]any, error) {
	data, err := yaml.Marshal(node)
	if err != nil {
		return nil, err
	}
	mapping := make(map[string]any)
	if err := yaml.Unmarshal(data, &mapping); err != nil {
		return nil, err
	}
	return mapping, nil
}

// provider 文件就是一份只带 proxies 段的 clash 配置，复用同一套解析与别名防护。
func previewProviderNodes(content []byte) []PreviewNode {
	mappings, err := providerProxyMappings(content)
	if err != nil {
		return nil
	}
	var nodes []PreviewNode
	for _, proxy := range mappings {
		name := scalar(proxy, "name")
		if blank(name) {
			continue
		}
		nodes = append(nodes, PreviewNode{
			Name:   name,
			Type:   scalar(proxy, "type"),
			Server: scalar(proxy, "server"),
			Port:   scalar(proxy, "port"),
		})
	}
	return nodes
}

func isTruthy(value string) bool {
	switch value {
	case "true", "True", "TRUE":
		return true
	}
	return false
}

// 内核组装代理组时按名字去重，预览保持一致；展开 include-all 与显式列表的交集不能重复显示。
func dedupe(names []string) []string {
	if len(names) == 0 {
		return []string{}
	}
	seen := make(map[string]bool, len(names))
	result := make([]string, 0, len(names))
	for _, name := range names {
		if seen[name] {
			continue
		}
		seen[name] = true
		result = append(result, name)
	}
	return result
}
