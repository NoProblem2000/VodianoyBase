-- v1.6.0 object kinds in the demo: ENUM type, trigger function + trigger, comments
CREATE TYPE sales.order_priority AS ENUM ('low', 'normal', 'high');
CREATE FUNCTION sales.touch_customer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RETURN NEW;
END $$;
CREATE TRIGGER customers_touch BEFORE UPDATE ON sales.customers
    FOR EACH ROW EXECUTE PROCEDURE sales.touch_customer();
COMMENT ON TABLE sales.customers IS 'Customers of the shop';
