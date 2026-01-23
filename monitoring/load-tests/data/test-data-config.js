/**
 * K6 Load Test - Test Data Configuration
 *
 * ID ranges for test data inserted via init-test-data.sql
 */

// ===== ID Ranges =====
export const CUSTOMER_ID_START = 10001;
export const CUSTOMER_ID_END = 10100;
export const CUSTOMER_COUNT = CUSTOMER_ID_END - CUSTOMER_ID_START + 1;  // 100

export const OWNER_ID_START = 20001;
export const OWNER_ID_END = 20050;
export const OWNER_COUNT = OWNER_ID_END - OWNER_ID_START + 1;  // 50

export const STORE_ID_START = 30001;
export const STORE_ID_END = 30100;
export const STORE_COUNT = STORE_ID_END - STORE_ID_START + 1;  // 100

export const WALLET_ID_START = 40001;
export const WALLET_ID_END = 40100;
export const WALLET_COUNT = WALLET_ID_END - WALLET_ID_START + 1;  // 100

export const CATEGORY_ID_START = 50001;
export const CATEGORY_ID_END = 50200;
export const CATEGORY_COUNT = CATEGORY_ID_END - CATEGORY_ID_START + 1;  // 200

export const MENU_ID_START = 60001;
export const MENU_ID_END = 60800;
export const MENU_COUNT = MENU_ID_END - MENU_ID_START + 1;  // 800

// ===== Group IDs (for group wallet tests) =====
export const GROUP_ID_START = 1;
export const GROUP_ID_END = 10;

// ===== Random ID Functions =====

/**
 * Get random customer ID from test data range
 * @returns {string} Customer ID as string
 */
export function getRandomCustomerId() {
  const id = CUSTOMER_ID_START + Math.floor(Math.random() * CUSTOMER_COUNT);
  return String(id);
}

/**
 * Get random owner ID from test data range
 * @returns {string} Owner ID as string
 */
export function getRandomOwnerId() {
  const id = OWNER_ID_START + Math.floor(Math.random() * OWNER_COUNT);
  return String(id);
}

/**
 * Get random store ID from test data range
 * @returns {number} Store ID as number
 */
export function getRandomStoreId() {
  return STORE_ID_START + Math.floor(Math.random() * STORE_COUNT);
}

/**
 * Get random wallet ID from test data range
 * @returns {number} Wallet ID as number
 */
export function getRandomWalletId() {
  return WALLET_ID_START + Math.floor(Math.random() * WALLET_COUNT);
}

/**
 * Get random category ID from test data range
 * @returns {number} Category ID as number
 */
export function getRandomCategoryId() {
  return CATEGORY_ID_START + Math.floor(Math.random() * CATEGORY_COUNT);
}

/**
 * Get random menu ID from test data range
 * @returns {number} Menu ID as number
 */
export function getRandomMenuId() {
  return MENU_ID_START + Math.floor(Math.random() * MENU_COUNT);
}

/**
 * Get random group ID
 * @returns {number} Group ID as number
 */
export function getRandomGroupId() {
  return GROUP_ID_START + Math.floor(Math.random() * (GROUP_ID_END - GROUP_ID_START + 1));
}

/**
 * Get store IDs owned by a specific owner
 * Each owner has 2 stores: ownerIndex * 2 + 1 and ownerIndex * 2 + 2
 * @param {string|number} ownerId - Owner ID
 * @returns {number[]} Array of store IDs
 */
export function getStoreIdsByOwner(ownerId) {
  const ownerIndex = Number(ownerId) - OWNER_ID_START;
  const storeBase = STORE_ID_START + (ownerIndex * 2);
  return [storeBase, storeBase + 1];
}

/**
 * Get a random store ID owned by a specific owner
 * @param {string|number} ownerId - Owner ID
 * @returns {number} Store ID
 */
export function getRandomStoreIdByOwner(ownerId) {
  const stores = getStoreIdsByOwner(ownerId);
  return stores[Math.floor(Math.random() * stores.length)];
}

// ===== VU Configuration per Scenario =====
export const VU_CONFIG = {
  // Customer scenarios (high load)
  storeCustomer: { target: 100, rampUp: '30s', steady: '2m', rampDown: '30s' },
  menuCustomer: { target: 100, rampUp: '30s', steady: '2m', rampDown: '30s' },
  favorite: { target: 100, rampUp: '30s', steady: '2m', rampDown: '30s' },
  customerProfile: { target: 100, rampUp: '30s', steady: '2m', rampDown: '30s' },
  notificationCustomer: { target: 100, rampUp: '30s', steady: '2m', rampDown: '30s' },
  auth: { target: 100, rampUp: '30s', steady: '2m', rampDown: '30s' },

  // Wallet scenarios (medium-high load)
  walletBalance: { target: 100, rampUp: '30s', steady: '2m', rampDown: '30s' },
  walletTransfer: { target: 50, rampUp: '30s', steady: '2m', rampDown: '30s' },

  // Payment scenarios (medium load)
  prepayment: { target: 50, rampUp: '30s', steady: '2m', rampDown: '30s' },
  payment: { target: 50, rampUp: '30s', steady: '2m', rampDown: '30s' },

  // Owner scenarios (medium load)
  storeOwner: { target: 50, rampUp: '30s', steady: '2m', rampDown: '30s' },
  menuOwner: { target: 50, rampUp: '30s', steady: '2m', rampDown: '30s' },
  menuCategory: { target: 50, rampUp: '30s', steady: '2m', rampDown: '30s' },
  chargeBonus: { target: 50, rampUp: '30s', steady: '2m', rampDown: '30s' },
  statistics: { target: 50, rampUp: '30s', steady: '2m', rampDown: '30s' },
  ownerProfile: { target: 50, rampUp: '30s', steady: '2m', rampDown: '30s' },
  notificationOwner: { target: 50, rampUp: '30s', steady: '2m', rampDown: '30s' },
};
