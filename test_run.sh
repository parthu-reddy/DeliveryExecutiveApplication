#!/bin/bash

echo "1. Creating Restaurant..."
REST_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "E2E Burger", "fssaiLicenseNumber": "1234567890", "gstin": "123456789012345", "pan": "ABCDE1234F", "cin": "U12345MH2023PTC123456"}' http://localhost:8092/api/v1/restaurants/onboard)
echo $REST_RESPONSE
REST_ID=$(echo $REST_RESPONSE | jq -r '.data.id')
echo "Restaurant ID: $REST_ID"

echo "2. Adding Menu Item..."
MENU_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "Veggie Burger", "price": 150.00, "isAvailable": true}' http://localhost:8092/api/v1/restaurants/$REST_ID/catalog/items)
echo $MENU_RESPONSE
MENU_ID=$(echo $MENU_RESPONSE | jq -r '.data.id')
echo "Menu Item ID: $MENU_ID"

echo "3. Creating Customer in DB..."
CUST_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
docker exec food_delivery_db psql -U postgres -d food_delivery -c "INSERT INTO customers (id, name, phone_number) VALUES ('$CUST_ID', 'Test Customer', '9999999999');"
echo "Customer ID: $CUST_ID"

echo "4. Creating Delivery Executive in DB..."
DEL_EXEC_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
docker exec food_delivery_db psql -U postgres -d food_delivery -c "INSERT INTO delivery_executives (id, name, phone_number, status) VALUES ('$DEL_EXEC_ID', 'Test Exec', '8888888888', 'AVAILABLE');"
echo "Delivery Executive ID: $DEL_EXEC_ID"

echo "5. Creating Order..."
ORDER_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{
  \"customerId\": \"$CUST_ID\",
  \"restaurantId\": \"$REST_ID\",
  \"items\": [
    {
      \"menuItemId\": \"$MENU_ID\",
      \"quantity\": 2
    }
  ]
}" http://localhost:8092/api/v1/orders)
echo $ORDER_RESPONSE
ORDER_ID=$(echo $ORDER_RESPONSE | jq -r '.data.id')
echo "Order ID: $ORDER_ID"

echo "6. Simulating Payment Webhook..."
PAYLOAD="{\"event\": \"payment.success\",\"payload\": {\"payment\": {\"entity\": {\"order_id\": \"$ORDER_ID\",\"status\": \"captured\",\"amount\": 300.00}}}}"
SIG=$(python3 -c "import hmac, hashlib, base64; print(base64.b64encode(hmac.new(b'test_secret', b'$PAYLOAD', hashlib.sha256).digest()).decode())")
WEBHOOK_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -H "X-Vyapar-Signature: $SIG" -d "$PAYLOAD" http://localhost:8092/api/v1/webhooks/vyapar)
echo $WEBHOOK_RESPONSE

echo "7. Waiting for Saga to process..."
sleep 5

echo "8. Checking Order Status..."
docker exec -i food_delivery_db psql -U postgres -d food_delivery -c "SELECT id, status FROM orders WHERE id = '$ORDER_ID';"
