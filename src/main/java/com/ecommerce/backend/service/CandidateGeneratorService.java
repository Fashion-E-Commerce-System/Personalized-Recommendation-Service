package com.ecommerce.backend.service;

import com.ecommerce.backend.domain.Order;
import com.ecommerce.backend.domain.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateGeneratorService {

    private final MongoTemplate mongoTemplate;
    private Map<String, Map<String,Long>> predNext;
    private List<String> popularItems;
    private Map<String, List<Order>> userOrdersCache;

    public void splitData() {
        LocalDate now = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");

        Query q1 = new Query(Criteria.where("date")
                .gte(now.minusWeeks(1).format(formatter))
                .lt(now.format(formatter)));
        Query q2 = new Query(Criteria.where("date")
                .gte(now.minusWeeks(2).format(formatter))
                .lt(now.minusWeeks(1).format(formatter)));
        Query q3 = new Query(Criteria.where("date")
                .gte(now.minusWeeks(3).format(formatter))
                .lt(now.minusWeeks(2).format(formatter)));
        Query q4 = new Query(Criteria.where("date")
                .gte(now.minusWeeks(4).format(formatter))
                .lt(now.minusWeeks(3).format(formatter)));

        List<Order> train1 = mongoTemplate.find(q1, Order.class);
        List<Order> train2 = mongoTemplate.find(q2, Order.class);
        List<Order> train3 = mongoTemplate.find(q3, Order.class);
        List<Order> test   = mongoTemplate.find(q4, Order.class);

        log.info("최근 3주차 데이터 분할 완료: {}, {}, {}", train1.size(), train2.size(), train3.size());

        List<Order> trainAll = new ArrayList<>();
        trainAll.addAll(train1);
        trainAll.addAll(train2);
        trainAll.addAll(train3);

        userOrdersCache = new HashMap<>();
        for (Order o : trainAll) {
            userOrdersCache.computeIfAbsent(o.getUsername(), k -> new ArrayList<>()).add(o);
        }

        predictNextItem(trainAll);
        getPopularItems(trainAll);
        showMap12(test, 12);
    }

    private void predictNextItem(List<Order> trainAll) {
        predNext = new HashMap<>();
        for (Order order : trainAll) {
            List<Product> products = order.getProducts();
            for (int i = 0; i < products.size() - 1; i++) {
                String current = products.get(i).getProductId();
                String next = products.get(i + 1).getProductId();
                predNext.computeIfAbsent(current, k -> new HashMap<>()).merge(next, 1L, Long::sum);
            }
        }
        Map<String, Map<String, Long>> filtered = new HashMap<>();
        for (Map.Entry<String, Map<String, Long>> entry : predNext.entrySet()) {
            String item = entry.getKey();
            Map<String, Long> nextMap = entry.getValue();
            long total = nextMap.values().stream().mapToLong(Long::longValue).sum();
            Map.Entry<String, Long> mostCommon = nextMap.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElse(null);
            if (mostCommon != null) {
                double ratio = (double) mostCommon.getValue() / total;
                if (total >= 5 && ratio >= 0.1) {
                    filtered.put(item, Map.of(mostCommon.getKey(), mostCommon.getValue()));
                }
            }
        }
        predNext = filtered;
        log.info("=== PredNext 결과 ===");
    }

    private void getPopularItems(List<Order> trainAll) {
        Map<String, Double> popularity = new HashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");

        List<LocalDate> orderDates = trainAll.stream()
                .map(o -> MonthDay.parse(o.getDate(), formatter).atYear(LocalDate.now().getYear()))
                .toList();

        LocalDate endDate = orderDates.stream().max(LocalDate::compareTo).orElse(LocalDate.now());

        for (int i = 0; i < trainAll.size(); i++) {
            Order o = trainAll.get(i);
            LocalDate orderDate = orderDates.get(i);
            long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(orderDate, endDate);
            double popFactor = 1.0 / (daysDiff == 0 ? 1 : daysDiff);
            for (Product product : o.getProducts()) {
                popularity.merge(product.getProductId(), popFactor, Double::sum);
            }
        }

        PriorityQueue<Map.Entry<String, Double>> pq =
                new PriorityQueue<>(Map.Entry.comparingByValue());
        for (Map.Entry<String, Double> entry : popularity.entrySet()) {
            pq.offer(entry);
            if (pq.size() > 20) pq.poll();
        }

        popularItems = pq.stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        log.info("인기 아이템 Top20: {}", popularItems);
    }

    public Map<String, List<String>> predict(List<String> users, Map<String, Set<String>> truthDict) {
        Map<String, List<String>> userPredictions = new HashMap<>();
        for (String user : users) {
            List<Order> orders = userOrdersCache.getOrDefault(user, Collections.emptyList());
            List<String> userOutput = new ArrayList<>();

            // 최근 train 구간별 최대 12개만 추출
            orders.stream()
                    .flatMap(o -> o.getProducts().stream())
                    .map(Product::getProductId)
                    .limit(12)
                    .forEach(userOutput::add);

            // PredNext 추가 (이미 없는 경우만)
            List<String> nextPreds = userOutput.stream()
                    .filter(predNext::containsKey)
                    .map(item -> predNext.get(item).keySet().iterator().next())
                    .filter(next -> !userOutput.contains(next))
                    .toList();
            userOutput.addAll(nextPreds);

            // 인기 아이템 보충
            int need = 24 - userOutput.size();
            if (need > 0) {
                userOutput.addAll(popularItems.stream()
                        .filter(p -> !userOutput.contains(p))
                        .limit(need)
                        .toList());
            }

            // 중복 제거
            List<String> finalOutput = new ArrayList<>(new LinkedHashSet<>(userOutput));
            userPredictions.put(user, finalOutput);
        }
        return userPredictions;
    }

    public double apAtK(List<String> recommended, Set<String> truths, int K) {
        List<String> recs = recommended.stream().distinct().limit(K).toList();
        if (truths.isEmpty()) return 0.0;
        int tp = 0;
        double apSum = 0.0;
        for (int k = 0; k < recs.size(); k++) {
            String item = recs.get(k);
            if (truths.contains(item)) {
                tp++;
                apSum += (double) tp / (k + 1);
            }
        }
        return apSum / Math.min(truths.size(), K);
    }

    public double showMap12(List<Order> testData, int K) {
        List<String> users = testData.stream()
                .map(Order::getUsername)
                .distinct()
                .toList();
        Map<String, Set<String>> truthDict = testData.stream()
                .collect(Collectors.groupingBy(
                        Order::getUsername,
                        Collectors.flatMapping(
                                o -> o.getProducts().stream()
                                        .map(Product::getProductId),
                                Collectors.toSet()
                        )
                ));
        Map<String, List<String>> predDict = predict(users, truthDict);
        List<Double> apValues = new ArrayList<>();
        int processed = 0;
        for (String uid : users) {
            List<String> recs = predDict.getOrDefault(uid, Collections.emptyList());
            Set<String> truths = truthDict.getOrDefault(uid, Collections.emptySet());
            double ap = apAtK(recs, truths, K);
            apValues.add(ap);
            processed++;
            log.info("진행 상황: {}/{} 유저 처리 완료 (현재 유저: {}, AP@{} = {:.4f})",
                    processed, users.size(), uid, K, ap);
        }
        double mapScore = apValues.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
        log.info("MAP@{} Score: {}", K, String.format("%.4f", mapScore));
        return mapScore;
    }
}