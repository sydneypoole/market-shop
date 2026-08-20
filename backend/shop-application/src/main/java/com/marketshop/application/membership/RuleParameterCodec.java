package com.marketshop.application.membership;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketshop.domain.shared.DomainException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single JSON boundary for versioned business rules.
 *
 * Publication accepts only the known code/type pair and the canonical field
 * set. Persisted reads additionally repair the legacy V2 omissions in memory;
 * no historical row is rewritten by this codec.
 */
public final class RuleParameterCodec {

    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    public static final int DEFAULT_PROOF_RETENTION_DAYS = 180;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> SALES_SCENES = Set.of("UPGRADE", "REPURCHASE");
    private static final Map<String, String> CODE_TYPES;

    static {
        Map<String, String> codes = new LinkedHashMap<>();
        codes.put("EXPERIENCE_OFFICER_UPGRADE", "SELF_ORDER_TASK");
        codes.put("SUPER_MEMBER_UPGRADE", "SELF_ORDER_TASK");
        codes.put("DIVIDEND_MEMBER_QUALIFICATION", "DIRECT_REFERRAL_TASK");
        codes.put("DIRECT_REFERRAL_POINTS", "DIRECT_REFERRAL_POINTS");
        codes.put("REPURCHASE_RELEASE", "FROZEN_POINTS_RELEASE");
        codes.put("DIVIDEND_INACTIVITY_DOWNGRADE", "INACTIVITY_DOWNGRADE");
        codes.put("ORDER_TIMERS", "ORDER_TIMER");
        CODE_TYPES = Collections.unmodifiableMap(codes);
    }

    private RuleParameterCodec() {
    }

    public record Decoded(RuleParameters parameters, String normalizedJson, boolean repaired) {
    }

    public static Decoded decode(String ruleCode, String ruleType, String parametersJson) {
        return decodeForPublication(ruleCode, ruleType, parametersJson);
    }

    public static Decoded decodeForPublication(String ruleCode, String ruleType, String parametersJson) {
        return decodeInternal(ruleCode, ruleType, parametersJson, false);
    }

    public static Decoded decodePersisted(String ruleCode, String ruleType, String parametersJson) {
        return decodeInternal(ruleCode, ruleType, parametersJson, true);
    }

    public static String normalize(String ruleCode, String ruleType, String parametersJson) {
        return decodeForPublication(ruleCode, ruleType, parametersJson).normalizedJson();
    }

    public static String normalizePersisted(String ruleCode, String ruleType, String parametersJson) {
        return decodePersisted(ruleCode, ruleType, parametersJson).normalizedJson();
    }

    public static boolean supports(String ruleCode, String ruleType) {
        return ruleCode != null
                && ruleType != null
                && ruleType.equals(CODE_TYPES.get(ruleCode.trim()));
    }

    public static String expectedType(String ruleCode) {
        return CODE_TYPES.get(ruleCode == null ? null : ruleCode.trim());
    }

    public static Set<String> supportedRuleCodes() {
        return CODE_TYPES.keySet();
    }

    private static Decoded decodeInternal(
            String rawRuleCode,
            String rawRuleType,
            String parametersJson,
            boolean persistedRead
    ) {
        String ruleCode = rawRuleCode == null ? null : rawRuleCode.trim();
        if (ruleCode == null || rawRuleType == null || !supports(ruleCode, rawRuleType)) {
            throw new DomainException("RULE_CODE_TYPE_INVALID", "规则编码与类型不匹配");
        }
        JsonNode root = parseObject(parametersJson);
        return switch (rawRuleType) {
            case "SELF_ORDER_TASK" -> decodeSelf(ruleCode, root, persistedRead);
            case "DIRECT_REFERRAL_TASK" -> decodeDirect(ruleCode, root, persistedRead);
            case "DIRECT_REFERRAL_POINTS" -> decodePoints(ruleCode, root, persistedRead);
            case "FROZEN_POINTS_RELEASE" -> decodeRelease(ruleCode, root, persistedRead);
            case "INACTIVITY_DOWNGRADE" -> decodeInactivity(ruleCode, root);
            case "ORDER_TIMER" -> decodeTimer(ruleCode, root, persistedRead);
            default -> throw invalid("未知规则类型");
        };
    }

    private static Decoded decodeSelf(String code, JsonNode root, boolean persistedRead) {
        Set<String> keys = Set.of("minimumCompletedOrderAmountFen", "eligibleSalesScenes", "targetLevel");
        rejectUnknown(root, keys);
        long minimum = positiveLong(root, "minimumCompletedOrderAmountFen");
        List<String> scenes = salesScenes(root, "eligibleSalesScenes", "UPGRADE", persistedRead);
        String target = text(root, "targetLevel");
        SelfOrderTaskParameters parameters = new SelfOrderTaskParameters(minimum, scenes, target);
        return decoded(parameters, false);
    }

    private static Decoded decodeDirect(String code, JsonNode root, boolean persistedRead) {
        Set<String> keys = Set.of(
                "requiredCompletedDirectReferrals",
                "minimumReferralOrderAmountFen",
                "eligibleSalesScenes",
                "requiredReferralLevel",
                "targetLevel"
        );
        rejectUnknown(root, keys);
        int required = boundedInt(root, "requiredCompletedDirectReferrals", 1, 100_000);
        long minimum = positiveLong(root, "minimumReferralOrderAmountFen");
        List<String> scenes = salesScenes(root, "eligibleSalesScenes", "UPGRADE", persistedRead);
        String requiredLevel = text(root, "requiredReferralLevel");
        String target = text(root, "targetLevel");
        return decoded(new DirectReferralTaskParameters(required, minimum, scenes, requiredLevel, target), false);
    }

    private static Decoded decodePoints(String code, JsonNode root, boolean persistedRead) {
        Set<String> keys = Set.of(
                "qualificationCount",
                "pointsStartOrdinal",
                "totalPoints",
                "availableAPoints",
                "frozenBPoints",
                "maxRewardDepth",
                "eligibleSalesScenes"
        );
        rejectUnknown(root, keys);

        boolean legacyMinimal = !root.has("qualificationCount")
                && !root.has("totalPoints")
                && !root.has("maxRewardDepth")
                && !root.has("eligibleSalesScenes");
        if (legacyMinimal && !persistedRead) {
            throw invalid("DIRECT_REFERRAL_POINTS 缺少规范必填参数");
        }
        if (!legacyMinimal && (!root.has("qualificationCount")
                || !root.has("totalPoints")
                || !root.has("maxRewardDepth"))) {
            throw invalid("DIRECT_REFERRAL_POINTS 缺少必填参数");
        }

        int pointsStart = boundedInt(root, "pointsStartOrdinal", legacyMinimal ? 1 : 2, 100_000);
        long available = nonNegativeLong(root, "availableAPoints");
        long frozen = nonNegativeLong(root, "frozenBPoints");
        long total;
        int qualification;
        int maxDepth;
        if (legacyMinimal) {
            qualification = pointsStart - 1;
            total = safeSum(available, frozen, "A/B 积分总量超出安全范围");
            maxDepth = 1;
        } else {
            qualification = boundedInt(root, "qualificationCount", 1, 100_000);
            if (pointsStart <= qualification) {
                throw invalid("pointsStartOrdinal 必须大于 qualificationCount");
            }
            total = nonNegativeLong(root, "totalPoints");
            maxDepth = boundedInt(root, "maxRewardDepth", 1, 1);
            long calculated = safeSum(available, frozen, "A/B 积分总量超出安全范围");
            if (total != calculated || total <= 0) {
                throw invalid("totalPoints 必须等于 A/B 积分之和且大于 0");
            }
        }
        if (total <= 0) {
            throw invalid("A/B 积分至少一项必须大于 0");
        }
        List<String> scenes = salesScenes(root, "eligibleSalesScenes", "UPGRADE", persistedRead);
        DirectReferralPointsParameters parameters = new DirectReferralPointsParameters(
                qualification, pointsStart, total, available, frozen, maxDepth, scenes
        );
        return decoded(parameters, legacyMinimal || !root.has("eligibleSalesScenes"));
    }

    private static Decoded decodeRelease(String code, JsonNode root, boolean persistedRead) {
        Set<String> keys = Set.of(
                "eligibleSalesScenes",
                "minimumCompletedOrderAmountFen",
                "releaseMode",
                "releasePointsPerOrder",
                "batchOrder"
        );
        rejectUnknown(root, keys);
        List<String> scenes = salesScenes(root, "eligibleSalesScenes", "REPURCHASE", persistedRead);
        long minimum = positiveLong(root, "minimumCompletedOrderAmountFen");
        String releaseMode = exactText(root, "releaseMode", "FIXED");
        long releasePoints = positiveLong(root, "releasePointsPerOrder");
        String batchOrder = exactText(root, "batchOrder", "FIFO");
        return decoded(new FrozenPointsReleaseParameters(
                scenes, minimum, releaseMode, releasePoints, batchOrder
        ), false);
    }

    private static Decoded decodeInactivity(String code, JsonNode root) {
        Set<String> keys = Set.of("inactiveMonths", "sourceLevel", "targetLevel");
        rejectUnknown(root, keys);
        int months = boundedInt(root, "inactiveMonths", 1, 60);
        String source = text(root, "sourceLevel");
        String target = text(root, "targetLevel");
        if (source.equals(target)) {
            throw invalid("降级前后等级不能相同");
        }
        return decoded(new InactivityDowngradeParameters(months, source, target), false);
    }

    private static Decoded decodeTimer(String code, JsonNode root, boolean persistedRead) {
        Set<String> keys = Set.of(
                "autoReceiveDays",
                "autoReceiveDaysAfterShipment",
                "afterSaleDaysAfterCompletion",
                "pendingSuperiorTimeoutDays",
                "pendingAdminReviewTimeoutDays",
                "pendingShipmentTimeoutDays",
                "awaitingReturnTimeoutDays",
                "returnShippedTimeoutDays",
                "offlineRefundTimeoutDays",
                "buyerRefundConfirmTimeoutDays",
                "proofRetentionDays",
                "maxProofFiles",
                "maxProofSizeBytes"
        );
        rejectUnknown(root, keys);
        if (root.has("autoReceiveDays") && root.has("autoReceiveDaysAfterShipment")) {
            throw invalid("autoReceiveDays 与 autoReceiveDaysAfterShipment 不能同时存在");
        }
        if (root.has("autoReceiveDaysAfterShipment") && !persistedRead) {
            throw invalid("autoReceiveDays 不能为空");
        }
        if (!root.has("autoReceiveDays") && !root.has("autoReceiveDaysAfterShipment")) {
            throw invalid("autoReceiveDays 不能为空");
        }
        String autoReceiveField = root.has("autoReceiveDays")
                ? "autoReceiveDays"
                : "autoReceiveDaysAfterShipment";
        int autoReceive = boundedInt(root, autoReceiveField, 1, 365);
        int afterSale = boundedInt(root, "afterSaleDaysAfterCompletion", 1, 365);
        int pendingSuperior = boundedInt(root, "pendingSuperiorTimeoutDays", 1, 365);
        int pendingAdmin = boundedInt(root, "pendingAdminReviewTimeoutDays", 1, 365);
        int pendingShipment = boundedInt(root, "pendingShipmentTimeoutDays", 1, 365);
        int awaitingReturn = boundedInt(root, "awaitingReturnTimeoutDays", 1, 365);
        int returnShipped = boundedInt(root, "returnShippedTimeoutDays", 1, 365);
        int offlineRefund = boundedInt(root, "offlineRefundTimeoutDays", 1, 365);
        int buyerRefundConfirm = boundedInt(root, "buyerRefundConfirmTimeoutDays", 1, 365);
        boolean retentionRepaired = persistedRead
                && !validBoundedInteger(root.get("proofRetentionDays"), 1, 3650);
        int retention = retentionRepaired
                ? DEFAULT_PROOF_RETENTION_DAYS
                : boundedInt(root, "proofRetentionDays", 1, 3650);
        int maxFiles = boundedInt(root, "maxProofFiles", 1, 20);
        long maxBytes = boundedLong(root, "maxProofSizeBytes", 1024, 20L * 1024 * 1024);
        OrderTimerParameters parameters = new OrderTimerParameters(
                autoReceive, afterSale, pendingSuperior, pendingAdmin, pendingShipment,
                awaitingReturn, returnShipped, offlineRefund, buyerRefundConfirm,
                retention, maxFiles, maxBytes
        );
        return decoded(parameters, retentionRepaired);
    }

    private static Decoded decoded(RuleParameters parameters, boolean repaired) {
        return new Decoded(parameters, encode(parameters), repaired);
    }

    private static JsonNode parseObject(String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            throw invalid("规则参数不能为空");
        }
        try {
            JsonNode root = JSON.readTree(parametersJson);
            if (root == null || !root.isObject()) {
                throw invalid("规则参数必须是 JSON 对象");
            }
            return root;
        } catch (DomainException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("规则参数不是合法 JSON");
        }
    }

    private static void rejectUnknown(JsonNode root, Set<String> allowed) {
        root.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw invalid("不支持的规则参数：" + name);
            }
        });
    }

    private static List<String> salesScenes(
            JsonNode root,
            String field,
            String expected,
            boolean allowMissing
    ) {
        JsonNode value = root.get(field);
        if (value == null) {
            if (allowMissing) {
                return List.of(expected);
            }
            throw invalid(field + " 必须为 [" + expected + "]");
        }
        if (!value.isArray() || value.size() != 1 || !value.get(0).isTextual()
                || !expected.equals(value.get(0).textValue())
                || !SALES_SCENES.contains(expected)) {
            throw invalid(field + " 当前仅支持 [" + expected + "]");
        }
        return List.of(expected);
    }

    private static long positiveLong(JsonNode root, String field) {
        return boundedLong(root, field, 1, MAX_SAFE_INTEGER);
    }

    private static long nonNegativeLong(JsonNode root, String field) {
        return boundedLong(root, field, 0, MAX_SAFE_INTEGER);
    }

    private static long boundedLong(JsonNode root, String field, long minimum, long maximum) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field + " 必须是整数");
        }
        long number = value.longValue();
        if (number < minimum) {
            throw invalid(field + " 必须在 " + minimum + " 到 " + maximum + " 之间");
        }
        if (number > maximum) {
            throw invalid(maximum == MAX_SAFE_INTEGER ? field + " 超出安全范围" :
                    field + " 必须在 " + minimum + " 到 " + maximum + " 之间");
        }
        return number;
    }

    private static int boundedInt(JsonNode root, String field, int minimum, int maximum) {
        long value = boundedLong(root, field, minimum, maximum);
        return (int) value;
    }

    private static boolean validBoundedInteger(JsonNode value, long minimum, long maximum) {
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToLong()
                && value.longValue() >= minimum
                && value.longValue() <= maximum;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " 不能为空");
        }
        return value.textValue().trim();
    }

    private static String exactText(JsonNode root, String field, String expected) {
        String value = text(root, field);
        if (!expected.equals(value)) {
            throw invalid(field + " 当前仅支持 " + expected);
        }
        return value;
    }

    private static long safeSum(long left, long right, String message) {
        try {
            long total = Math.addExact(left, right);
            if (total > MAX_SAFE_INTEGER) {
                throw invalid(message);
            }
            return total;
        } catch (ArithmeticException exception) {
            throw invalid(message);
        }
    }

    private static String encode(RuleParameters parameters) {
        Map<String, Object> values = new LinkedHashMap<>();
        switch (parameters) {
            case SelfOrderTaskParameters value -> {
                values.put("minimumCompletedOrderAmountFen", value.minimumCompletedOrderAmountFen());
                values.put("eligibleSalesScenes", value.eligibleSalesScenes());
                values.put("targetLevel", value.targetLevel());
            }
            case DirectReferralTaskParameters value -> {
                values.put("requiredCompletedDirectReferrals", value.requiredCompletedDirectReferrals());
                values.put("minimumReferralOrderAmountFen", value.minimumReferralOrderAmountFen());
                values.put("eligibleSalesScenes", value.eligibleSalesScenes());
                values.put("requiredReferralLevel", value.requiredReferralLevel());
                values.put("targetLevel", value.targetLevel());
            }
            case DirectReferralPointsParameters value -> {
                values.put("qualificationCount", value.qualificationCount());
                values.put("pointsStartOrdinal", value.pointsStartOrdinal());
                values.put("totalPoints", value.totalPoints());
                values.put("availableAPoints", value.availableAPoints());
                values.put("frozenBPoints", value.frozenBPoints());
                values.put("maxRewardDepth", value.maxRewardDepth());
                values.put("eligibleSalesScenes", value.eligibleSalesScenes());
            }
            case FrozenPointsReleaseParameters value -> {
                values.put("eligibleSalesScenes", value.eligibleSalesScenes());
                values.put("minimumCompletedOrderAmountFen", value.minimumCompletedOrderAmountFen());
                values.put("releaseMode", value.releaseMode());
                values.put("releasePointsPerOrder", value.releasePointsPerOrder());
                values.put("batchOrder", value.batchOrder());
            }
            case InactivityDowngradeParameters value -> {
                values.put("inactiveMonths", value.inactiveMonths());
                values.put("sourceLevel", value.sourceLevel());
                values.put("targetLevel", value.targetLevel());
            }
            case OrderTimerParameters value -> {
                values.put("autoReceiveDays", value.autoReceiveDays());
                values.put("afterSaleDaysAfterCompletion", value.afterSaleDaysAfterCompletion());
                values.put("pendingSuperiorTimeoutDays", value.pendingSuperiorTimeoutDays());
                values.put("pendingAdminReviewTimeoutDays", value.pendingAdminReviewTimeoutDays());
                values.put("pendingShipmentTimeoutDays", value.pendingShipmentTimeoutDays());
                values.put("awaitingReturnTimeoutDays", value.awaitingReturnTimeoutDays());
                values.put("returnShippedTimeoutDays", value.returnShippedTimeoutDays());
                values.put("offlineRefundTimeoutDays", value.offlineRefundTimeoutDays());
                values.put("buyerRefundConfirmTimeoutDays", value.buyerRefundConfirmTimeoutDays());
                values.put("proofRetentionDays", value.proofRetentionDays());
                values.put("maxProofFiles", value.maxProofFiles());
                values.put("maxProofSizeBytes", value.maxProofSizeBytes());
            }
        }
        return write(values);
    }

    private static String write(Map<String, Object> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception exception) {
            throw new DomainException("RULE_PARAMETERS_INVALID", "规则参数无法规范化", exception);
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException("RULE_PARAMETERS_INVALID", message);
    }
}
