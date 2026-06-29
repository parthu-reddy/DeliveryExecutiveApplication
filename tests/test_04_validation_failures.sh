#!/bin/bash
set -e

echo "--- START: Validation Failures Scenario ---"

echo "1. Onboarding Restaurant..."
REST_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "Validation Burger", "fssaiLicenseNumber": "1234567893", "gstin": "123456789012348", "pan": "ABCDE1237F", "cin": "U12345MH2023PTC123459", "bankAccountNumber": "1234567890", "ifscCode": "HDFC0001234", "lat": 12.9716, "lng": 77.5946}' http://localhost:8092/api/v1/restaurants/onboard)
REST_ID=$(echo $REST_RESPONSE | jq -r '.data.id')

echo "2. Adding Unavailable Menu Item..."
MENU_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "Sold Out Meal", "price": 150.00, "isAvailable": false}' http://localhost:8092/api/v1/restaurants/$REST_ID/catalog/items)
MENU_ID=$(echo $MENU_RESPONSE | jq -r '.data.id')

echo "3. Creating Customer in DB..."
CUST_PHONE="999$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
CUST_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
docker exec -i food_delivery_db psql -U postgres -d food_delivery -c "INSERT INTO customers (id, name, phone_number) VALUES ('$CUST_ID', 'Test Customer', '$CUST_PHONE');" > /dev/null

echo "4. Placing Order with Unavailable Item (Should Fail)..."
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" -d "{
  \"customerId\": \"$CUST_ID\",
  \"restaurantId\": \"$REST_ID\",
  \"items\": [{\"menuItemId\": \"$MENU_ID\", \"quantity\": 2}]
}" http://localhost:8092/api/v1/orders)

if [ "$HTTP_STATUS" != "500" ] && [ "$HTTP_STATUS" != "400" ]; then
    echo "FAIL: Expected HTTP 400 or 500 for unavailable item, got $HTTP_STATUS"
    exit 1
else
    echo "Successfully caught validation error for unavailable item (HTTP $HTTP_STATUS)"
fi

echo "5. Placing Order for Non-Existent Restaurant (Should Fail)..."
FAKE_REST_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" -d "{
  \"customerId\": \"$CUST_ID\",
  \"restaurantId\": \"$FAKE_REST_ID\",
  \"items\": [{\"menuItemId\": \"$MENU_ID\", \"quantity\": 2}]
}" http://localhost:8092/api/v1/orders)

if [ "$HTTP_STATUS" != "500" ] && [ "$HTTP_STATUS" != "400" ] && [ "$HTTP_STATUS" != "404" ]; then
    echo "FAIL: Expected HTTP error for non-existent restaurant, got $HTTP_STATUS"
    exit 1
else
    echo "Successfully caught validation error for fake restaurant (HTTP $HTTP_STATUS)"
fi

echo "--- SUCCESS: Validation Failures Scenario Completed ---"
exit 0
