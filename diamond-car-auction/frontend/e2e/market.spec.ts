import { expect, test, type Page } from '@playwright/test';

async function signIn(page: Page, account: string) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(account + '@diamond.test');
  await page.getByLabel('Password').fill('DiamondDemo2026!');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).toHaveURL(/dashboard/);
}

function localTime(time: number) {
  const date = new Date(time);
  return new Date(time - date.getTimezoneOffset() * 60000).toISOString().slice(0, 19);
}

test('seller submits a listing, administrator approves and buyer wins an order', async ({ browser, baseURL }) => {
  const seller = await browser.newContext({ baseURL });
  const admin = await browser.newContext({ baseURL });
  const buyer = await browser.newContext({ baseURL });
  const sellerPage = await seller.newPage();
  const adminPage = await admin.newPage();
  const buyerPage = await buyer.newPage();
  try {
    await signIn(sellerPage, 'seller');
    await signIn(adminPage, 'admin');
    await signIn(buyerPage, 'buyer2');
    await sellerPage.getByRole('link', { name: 'Create listing' }).click();
    const model = 'M3 Test ' + Date.now();
    const values = {
      Make: 'BMW', Model: model, Trim: 'E46', 'Exterior color': 'Silver', 'Interior color': 'Black',
      Location: 'Portland, OR', 'Model year': '2004', Mileage: '65000', Description: 'Fictional E2E listing.',
      'Condition notes': 'Light bumper wear.', 'Service history': 'Recent oil change.',
      'Opening bid': '1000', 'Minimum increment': '250',
    };
    for (const [label, value] of Object.entries(values)) await sellerPage.getByLabel(label, { exact: true }).fill(value);
    await sellerPage.getByLabel('Upload JPEG or PNG').setInputFiles('public/cars/bmw.jpg');
    await expect(sellerPage.getByText('Cover', { exact: true })).toBeVisible();
    const now = Date.now();
    await sellerPage.getByLabel('Starts (local time)').fill(localTime(now + 25000));
    await sellerPage.getByLabel('Ends (local time)').fill(localTime(now + 49000));
    await sellerPage.getByRole('button', { name: 'Submit for review' }).click();
    await expect(sellerPage.getByText('In review', { exact: true }).first()).toBeVisible();
    await adminPage.reload();
    const listing = adminPage.locator('li').filter({ hasText: model });
    await listing.getByRole('button', { name: 'Approve', exact: true }).click();
    await expect(listing).toHaveCount(0);
    await buyerPage.goto('/browse?q=' + encodeURIComponent(model));
    await buyerPage.getByRole('link', { name: new RegExp(model) }).click();
    await expect(buyerPage.getByRole('button', { name: 'Review bid' })).toBeEnabled({ timeout: 45000 });
    await buyerPage.getByLabel(/^Amount/).fill('1000');
    await buyerPage.getByRole('button', { name: 'Review bid' }).click();
    await buyerPage.getByRole('button', { name: 'Confirm bid' }).click();
    await expect(buyerPage.getByText(/Bid accepted.*deadline was extended/)).toBeVisible();
    await expect(buyerPage.getByText('Sold', { exact: true })).toBeVisible({ timeout: 45000 });
    await buyerPage.getByRole('link', { name: 'Dashboard', exact: true }).click();
    await buyerPage.getByRole('button', { name: 'Orders', exact: true }).click();
    await buyerPage.getByRole('link', { name: new RegExp(model) }).click({ timeout: 25000 });
    await expect(buyerPage.getByText('SIMULATED CHECKOUT', { exact: true })).toBeVisible();
    await buyerPage.getByRole('button', { name: 'Simulate payment' }).click();
    await expect(buyerPage.getByText('Paid', { exact: true })).toBeVisible();
  } finally {
    await Promise.all([seller.close(), admin.close(), buyer.close()]);
  }
});

test('marketplace fits mobile and desktop with loaded photos', async ({ page }, info) => {
  for (const viewport of [{ width: 1440, height: 960 }, { width: 390, height: 844 }]) {
    await page.setViewportSize(viewport);
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Find your next drive.' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Recently sold' })).toBeVisible();
    await expect.poll(() => page.locator('.card-photo img').evaluateAll(images =>
      images.length > 0 && images.every(image => (image as HTMLImageElement).naturalWidth > 0))).toBeTruthy();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width);
    await page.screenshot({ path: info.outputPath('home-' + viewport.width + '.png'), fullPage: true });
    await page.getByRole('link', { name: 'Explore auctions' }).click();
    await expect(page.getByRole('heading', { name: 'Browse auctions' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width);
  }
});
