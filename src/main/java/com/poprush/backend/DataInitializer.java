package com.poprush.backend;

import com.poprush.backend.entity.Campaign;
import com.poprush.backend.entity.Product;
import com.poprush.backend.entity.User;
import com.poprush.backend.repository.CampaignRepository;
import com.poprush.backend.repository.ProductRepository;
import com.poprush.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Profile("dev") // 這樣 DataInitializer 只會在 dev 環境跑
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CampaignRepository campaignRepository;

    @Override
    public void run(String... args) {
        if (productRepository.count() > 0) return; // 只有在 products 表是空的時候才會插入，重啟不會重複塞。

        List<Product> products = productRepository.saveAll(List.of(
            product("iPhone 16 Pro 限量黑鈦", 39900, 500),
            product("Nike Air Jordan 1 限定款", 4500, 200),
            product("PlayStation 5 Digital Edition", 13980, 100),
            product("AirPods Pro 第二代", 7490, 300),
            product("Supreme Box Logo Hoodie", 6800, 50)
        ));

        userRepository.saveAll(List.of(
            user("Alice"), user("Bob"), user("Charlie"),
            user("Diana"), user("Edward"), user("Fiona"),
            user("George"), user("Hannah"), user("Ivan"), user("Julia")
        ));

        LocalDateTime now = LocalDateTime.now();
        campaignRepository.saveAll(List.of(
            campaign(products.get(0), 50, now.minusHours(1), now.plusHours(23)),
            campaign(products.get(1), 30, now.plusMinutes(30), now.plusHours(6)),
            campaign(products.get(2), 20, now.minusDays(1), now.plusDays(1)),
            campaign(products.get(3), 100, now.plusHours(2), now.plusHours(8)),
            campaign(products.get(4), 10, now.minusHours(2), now.plusMinutes(30))
        ));
    }

    private Product product(String name, int price, int totalStock) {
        Product p = new Product();
        p.setName(name);
        p.setPrice(price);
        p.setTotalStock(totalStock);
        return p;
    }

    private User user(String name) {
        User u = new User();
        u.setName(name);
        return u;
    }

    private Campaign campaign(Product product, int stock, LocalDateTime start, LocalDateTime end) {
        Campaign c = new Campaign();
        c.setProduct(product);
        c.setStock(stock);
        c.setStartTime(start);
        c.setEndTime(end);
        return c;
    }
}
