-- ============================================================
-- 农产品小店 进销存 MVP - Supabase 数据库结构
-- 在 Supabase 控制台 -> SQL Editor 里完整执行本文件即可。
-- 可重复执行（已使用 if not exists / create or replace）。
-- ============================================================

-- 让 gen_random_uuid() 可用
create extension if not exists "pgcrypto";

-- ---------- 商品资料库 ----------
create table if not exists public.products (
  id            uuid primary key default gen_random_uuid(),
  barcode       text unique,                       -- 条码（可为空，农产品可能没有标准条码）
  name          text not null,                     -- 商品名称
  category      text,                              -- 分类，如「蔬菜」「水果」
  unit          text not null default '件',         -- 单位：件 / 斤 / 公斤 / 包 ...
  sale_price    numeric(12,2) not null default 0,  -- 标准售价
  last_cost     numeric(12,2) not null default 0,  -- 最近一次进货成本（自动更新）
  stock         numeric(12,3) not null default 0,  -- 当前库存（支持小数，方便按重量计）
  reorder_level numeric(12,3) not null default 0,  -- 库存预警线
  note          text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

-- ---------- 厂商 / 供应商 ----------
create table if not exists public.suppliers (
  id         uuid primary key default gen_random_uuid(),
  name       text not null,
  contact    text,
  phone      text,
  note       text,
  created_at timestamptz not null default now()
);

-- ---------- 进货单 ----------
create table if not exists public.purchases (
  id            uuid primary key default gen_random_uuid(),
  supplier_id   uuid references public.suppliers(id) on delete set null,
  purchase_date date not null default current_date,  -- 进货日期（戳记）
  note          text,
  total         numeric(12,2) not null default 0,
  created_at    timestamptz not null default now(),
  created_by    uuid default auth.uid()
);

create table if not exists public.purchase_items (
  id          uuid primary key default gen_random_uuid(),
  purchase_id uuid not null references public.purchases(id) on delete cascade,
  product_id  uuid not null references public.products(id),
  qty         numeric(12,3) not null,
  unit_cost   numeric(12,2) not null,
  subtotal    numeric(12,2) not null
);

-- ---------- 销售单（结账）----------
create table if not exists public.sales (
  id             uuid primary key default gen_random_uuid(),
  sale_date      timestamptz not null default now(),  -- 结账时间戳记
  total          numeric(12,2) not null default 0,
  payment_method text default '现金',
  note           text,
  created_at     timestamptz not null default now(),
  created_by     uuid default auth.uid()
);

create table if not exists public.sale_items (
  id         uuid primary key default gen_random_uuid(),
  sale_id    uuid not null references public.sales(id) on delete cascade,
  product_id uuid not null references public.products(id),
  qty        numeric(12,3) not null,
  unit_price numeric(12,2) not null,
  subtotal   numeric(12,2) not null
);

-- ---------- 库存流水（审计追踪）----------
create table if not exists public.stock_movements (
  id         uuid primary key default gen_random_uuid(),
  product_id uuid not null references public.products(id),
  change     numeric(12,3) not null,   -- 正数=入库, 负数=出库
  type       text not null,            -- 'purchase' | 'sale' | 'adjust' | 'waste'
  ref_id     uuid,                     -- 关联进货单/销售单 id
  note       text,                     -- 盘点/报废原因
  created_at timestamptz not null default now()
);
-- 既有资料库补上 note 栏（盘点原因/报废原因），可重复执行
alter table public.stock_movements add column if not exists note text;

create index if not exists idx_products_barcode on public.products(barcode);
create index if not exists idx_purchase_items_purchase on public.purchase_items(purchase_id);
create index if not exists idx_sale_items_sale on public.sale_items(sale_id);
create index if not exists idx_stock_movements_product on public.stock_movements(product_id);

-- ============================================================
-- RPC：原子化「进货」—— 写进货单 + 明细 + 加库存 + 更新成本 + 流水
-- p_items 形如: [{"product_id":"...","qty":10,"unit_cost":3.5}, ...]
-- ============================================================
create or replace function public.create_purchase(
  p_supplier_id uuid,
  p_date        date,
  p_note        text,
  p_items       jsonb
) returns uuid
language plpgsql
security definer
as $$
declare
  v_purchase_id uuid;
  v_total       numeric(12,2) := 0;
  v_item        jsonb;
  v_product_id  uuid;
  v_qty         numeric(12,3);
  v_cost        numeric(12,2);
  v_subtotal    numeric(12,2);
begin
  insert into public.purchases (supplier_id, purchase_date, note, total)
  values (p_supplier_id, coalesce(p_date, current_date), p_note, 0)
  returning id into v_purchase_id;

  for v_item in select * from jsonb_array_elements(p_items)
  loop
    v_product_id := (v_item->>'product_id')::uuid;
    v_qty        := (v_item->>'qty')::numeric;
    v_cost       := (v_item->>'unit_cost')::numeric;
    v_subtotal   := round(v_qty * v_cost, 2);
    v_total      := v_total + v_subtotal;

    insert into public.purchase_items (purchase_id, product_id, qty, unit_cost, subtotal)
    values (v_purchase_id, v_product_id, v_qty, v_cost, v_subtotal);

    update public.products
       set stock      = stock + v_qty,
           last_cost  = v_cost,
           updated_at = now()
     where id = v_product_id;

    insert into public.stock_movements (product_id, change, type, ref_id)
    values (v_product_id, v_qty, 'purchase', v_purchase_id);
  end loop;

  update public.purchases set total = v_total where id = v_purchase_id;
  return v_purchase_id;
end;
$$;

-- ============================================================
-- RPC：原子化「结账」—— 写销售单 + 明细 + 扣库存 + 流水
-- p_items 形如: [{"product_id":"...","qty":2,"unit_price":5}, ...]
-- 库存不足会报错回滚。
-- ============================================================
create or replace function public.create_sale(
  p_items          jsonb,
  p_payment_method text,
  p_note           text
) returns uuid
language plpgsql
security definer
as $$
declare
  v_sale_id    uuid;
  v_total      numeric(12,2) := 0;
  v_item       jsonb;
  v_product_id uuid;
  v_qty        numeric(12,3);
  v_price      numeric(12,2);
  v_subtotal   numeric(12,2);
  v_stock      numeric(12,3);
  v_name       text;
begin
  insert into public.sales (total, payment_method, note)
  values (0, coalesce(p_payment_method, '现金'), p_note)
  returning id into v_sale_id;

  for v_item in select * from jsonb_array_elements(p_items)
  loop
    v_product_id := (v_item->>'product_id')::uuid;
    v_qty        := (v_item->>'qty')::numeric;
    v_price      := (v_item->>'unit_price')::numeric;
    v_subtotal   := round(v_qty * v_price, 2);
    v_total      := v_total + v_subtotal;

    select stock, name into v_stock, v_name from public.products where id = v_product_id for update;
    if v_stock is null then
      raise exception '商品不存在: %', v_product_id;
    end if;
    if v_stock < v_qty then
      raise exception '库存不足：% 当前库存 %，需要 %', v_name, v_stock, v_qty;
    end if;

    insert into public.sale_items (sale_id, product_id, qty, unit_price, subtotal)
    values (v_sale_id, v_product_id, v_qty, v_price, v_subtotal);

    update public.products
       set stock = stock - v_qty,
           updated_at = now()
     where id = v_product_id;

    insert into public.stock_movements (product_id, change, type, ref_id)
    values (v_product_id, -v_qty, 'sale', v_sale_id);
  end loop;

  update public.sales set total = v_total where id = v_sale_id;
  return v_sale_id;
end;
$$;

-- ============================================================
-- 行级安全 (RLS)：单店模式，已登录用户可读写全部数据
-- ============================================================
alter table public.products        enable row level security;
alter table public.suppliers       enable row level security;
alter table public.purchases       enable row level security;
alter table public.purchase_items  enable row level security;
alter table public.sales           enable row level security;
alter table public.sale_items      enable row level security;
alter table public.stock_movements enable row level security;

do $$
declare t text;
begin
  foreach t in array array[
    'products','suppliers','purchases','purchase_items',
    'sales','sale_items','stock_movements'
  ]
  loop
    execute format('drop policy if exists "auth_all" on public.%I', t);
    execute format(
      'create policy "auth_all" on public.%I for all to authenticated using (true) with check (true)',
      t
    );
  end loop;
end$$;
