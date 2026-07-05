package com.fooddelivery.delivery.filter;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryIdentityFilter extends OncePerRequestFilter {

    private final IDeliveryExecutiveRepository deliveryRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String phone = request.getHeader("X-User-Phone");
        
        if (phone != null && !phone.isEmpty()) {
            DeliveryExecutive executive = deliveryRepository.findByPhoneNumber(phone)
                    .orElseGet(() -> {
                        log.info("Creating new delivery executive seamlessly for phone {}", phone);
                        DeliveryExecutive newExecutive = new DeliveryExecutive();
                        newExecutive.setId(UUID.randomUUID());
                        newExecutive.setPhoneNumber(phone);
                        newExecutive.setStatus(DeliveryExecutiveStatus.OFFLINE);
                        return deliveryRepository.save(newExecutive);
                    });
                    
            request.setAttribute("DELIVERY_EXECUTIVE_ID", executive.getId().toString());
        }

        filterChain.doFilter(request, response);
    }
}
