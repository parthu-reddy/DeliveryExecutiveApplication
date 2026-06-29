#!/bin/bash
set -e

echo "--- START: Payment Timeout Sim Scenario ---"

echo "1. Onboarding Restaurant..."
REST_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "Timeout Burger", "fssaiLicenseNumber": "1234567897", "gstin": "123456789012353", "pan": "ABCDE1241F", "cin": "U12345MH2023PTC123463", "bankAccountNumber": "1234567890", "ifscCode": "HDFC0001234", "lat": 12.9716, "lng": 77.5946}' http://localhost:8092/api/v1/restaurants/onboard)
REST_ID=$(echo $REST_RESPONSE | jq -r '.data.id')

echo "2. Adding Menu Item..."
MENU_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "Timeout Meal", "price": 150.00, "isAvailable": true}' http://localhost:8092/api/v1/restaurants/$REST_ID/catalog/items)
MENU_ID=$(echo $MENU_RESPONSE | jq -r '.data.id')

echo "3. Creating Customer in DB..."
CUST_PHONE="999$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
CUST_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
docker exec -i food_delivery_db psql -U postgres -d food_delivery -c "INSERT INTO customers (id, name, phone_number) VALUES ('$CUST_ID', 'Test Customer', '$CUST_PHONE');" > /dev/null

echo "4. Placing Order..."
ORDER_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{
  \"customerId\": \"$CUST_ID\",
  \"restaurantId\": \"$REST_ID\",
  \"items\": [{\"menuItemId\": \"$MENU_ID\", \"quantity\": 2}]
}" http://localhost:8092/api/v1/orders)
ORDER_ID=$(echo $ORDER_RESPONSE | jq -r '.data.id')

echo "5. Verifying Order is in CREATED status..."
ORDER_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM orders WHERE id = '$ORDER_ID';" | xargs)
if [ "$ORDER_STATUS" != "CREATED" ]; then
    echo "FAIL: Expected CREATED, got $ORDER_STATUS"
    exit 1
fi

echo "6. Skipping Payment Webhook intentionally..."

echo "7. Invalid Attempt: Restaurant tries to accept UNPAID order..."
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8092/api/v1/restaurants/$REST_ID/fulfillment/orders/$ORDER_ID/accept)
if [ "$HTTP_STATUS" != "500" ] && [ "$HTTP_STATUS" != "400" ]; then
    echo "FAIL: Expected HTTP error for invalid transition, got $HTTP_STATUS"
    exit 1
else
    echo "Successfully caught invalid transition attempt for ACCEPT on UNPAID order (HTTP $HTTP_STATUS)"
fi

echo "--- SUCCESS: Payment Timeout Sim Scenario Completed ---"
exit 0
