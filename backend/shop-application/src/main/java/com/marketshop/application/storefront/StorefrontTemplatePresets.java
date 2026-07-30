package com.marketshop.application.storefront;

import com.marketshop.domain.shared.DomainException;

import java.util.Map;

final class StorefrontTemplatePresets {

    private static final Map<String, Preset> VALUES = Map.of(
            "EDITORIAL", new Preset(
                    """
                    {"primary":"#173F35","accent":"#C75B45","canvas":"#F4F0E8","surface":"#FFFEFA","ink":"#17201C","muted":"#707970","radius":"24px","headingFont":"serif"}
                    """,
                    """
                    {"schemaVersion":1,"sections":[
                      {"id":"editorial-announcement","type":"ANNOUNCEMENT","enabled":true,"settings":{"limit":3,"style":"ticker"}},
                      {"id":"editorial-hero","type":"HERO","enabled":true,"settings":{"eyebrow":"本期编辑甄选","title":"把日常过成，值得收藏的篇章","description":"从产地、工艺到日常体验，我们替你认真筛选每一件好物。","primaryLabel":"浏览本期精选","primaryLink":"#products","contentType":"BANNER"}},
                      {"id":"editorial-categories","type":"CATEGORY_NAV","enabled":true,"settings":{"title":"按生活场景探索"}},
                      {"id":"editorial-products","type":"PRODUCT_COLLECTION","enabled":true,"settings":{"eyebrow":"SELECTED OBJECTS","title":"值得反复使用的日常之物","description":"简洁、可靠，也保留一点让人愉悦的细节。","limit":8,"columns":4,"scene":"ALL"}},
                      {"id":"editorial-story","type":"CONTENT_STORY","enabled":true,"settings":{"contentType":"HELP","layout":"split"}},
                      {"id":"editorial-benefits","type":"SERVICE_BENEFITS","enabled":true,"settings":{"items":["严格甄选","上级确认","平台审核","售后留痕"]}},
                      {"id":"editorial-links","type":"QUICK_LINKS","enabled":true,"settings":{"title":"继续探索"}}
                    ]}
                    """
            ),
            "VIBRANT", new Preset(
                    """
                    {"primary":"#171717","accent":"#FF5A36","canvas":"#F7F42E","surface":"#FFFDF4","ink":"#111111","muted":"#595959","radius":"16px","headingFont":"sans"}
                    """,
                    """
                    {"schemaVersion":1,"sections":[
                      {"id":"vibrant-announcement","type":"ANNOUNCEMENT","enabled":true,"settings":{"limit":5,"style":"ticker"}},
                      {"id":"vibrant-hero","type":"HERO","enabled":true,"settings":{"eyebrow":"TODAY IS A GOOD DAY","title":"今天，就挑点真正好用的","description":"直给的价格信息、清晰的规格选择，让下单更快一步。","primaryLabel":"马上开逛","primaryLink":"#products","contentType":"BANNER"}},
                      {"id":"vibrant-categories","type":"CATEGORY_NAV","enabled":true,"settings":{"title":"热门分类"}},
                      {"id":"vibrant-links","type":"QUICK_LINKS","enabled":true,"settings":{"title":"快捷入口"}},
                      {"id":"vibrant-products","type":"PRODUCT_COLLECTION","enabled":true,"settings":{"eyebrow":"HOT PICKS","title":"本周大家都在买","description":"高频好物集中陈列，快速比较，直接选择。","limit":12,"columns":4,"scene":"ALL"}},
                      {"id":"vibrant-benefits","type":"SERVICE_BENEFITS","enabled":true,"settings":{"items":["现货库存","线下确认","极速审核","售后可追踪"]}}
                    ]}
                    """
            ),
            "MINIMAL", new Preset(
                    """
                    {"primary":"#191919","accent":"#8B7355","canvas":"#F7F7F5","surface":"#FFFFFF","ink":"#171717","muted":"#747474","radius":"4px","headingFont":"sans"}
                    """,
                    """
                    {"schemaVersion":1,"sections":[
                      {"id":"minimal-hero","type":"HERO","enabled":true,"settings":{"eyebrow":"ESSENTIAL COLLECTION","title":"少一点，但每一件都更好","description":"克制的选择，清楚的材料与规格，把注意力重新交还给产品。","primaryLabel":"查看系列","primaryLink":"#products","contentType":"BANNER"}},
                      {"id":"minimal-products","type":"PRODUCT_COLLECTION","enabled":true,"settings":{"eyebrow":"THE COLLECTION","title":"日常精选","description":"不追逐短暂潮流，只留下经得起长期使用的物品。","limit":8,"columns":3,"scene":"ALL"}},
                      {"id":"minimal-story","type":"CONTENT_STORY","enabled":true,"settings":{"contentType":"HELP","layout":"full"}},
                      {"id":"minimal-categories","type":"CATEGORY_NAV","enabled":true,"settings":{"title":"分类"}},
                      {"id":"minimal-benefits","type":"SERVICE_BENEFITS","enabled":true,"settings":{"items":["精选商品","透明规格","完整履约","可追溯售后"]}}
                    ]}
                    """
            )
    );

    private StorefrontTemplatePresets() {
    }

    static Preset get(String presetType) {
        Preset preset = VALUES.get(presetType);
        if (preset == null) {
            throw new DomainException("STOREFRONT_TEMPLATE_PRESET_INVALID", "模板预设类型无效");
        }
        return preset;
    }

    record Preset(String designTokensJson, String layoutJson) {
    }
}
