(ns cli.core
  (:require [cli.io :as cio]
            [etaoin.api :as e]
            [taoensso.timbre :as timbre]))

(defn go-if-not-already
  [driver url]
  (when-not (= (e/get-url driver) url)
    (e/go driver url)))

(defn wait-and-f
  [f driver selector]
  (e/wait-visible driver selector)
  (f driver selector))

(def wait-and-click
  (partial wait-and-f e/click))

(def wait-and-fill
  (partial wait-and-f e/fill))

(def wanted-products
  ["https://www.gsuplementos.com.br/creatina-monohidratada-250gr-growth-supplements-p985931"
   "https://www.gsuplementos.com.br/creatina-250g-creapure-growth-supplements-p985824"])

(def login-url
  "https://www.gsuplementos.com.br/checkout/acesso")

(def cart-url
  "https://www.gsuplementos.com.br/checkout/carrinho/")

(defn login
  [driver]
  (let [checkout-button-selector {:css ".botaoCheckoutAcesso"}
        input-wrapper-selector   {:css ".mainBox-conteudo-form-input-label"}

        email-selector    {:tag :input
                           :css "[type=email]"}
        password-selector {:tag :input
                           :css "[type=password]"}]

    (e/go driver login-url)
    (e/wait-visible driver input-wrapper-selector {:timeout 20})

    (e/click driver input-wrapper-selector)
    (e/wait-visible driver email-selector)
    (e/fill driver email-selector (System/getenv "GUSER"))
    (e/click driver checkout-button-selector)

    (e/wait-visible driver password-selector {:timeout 20})
    (e/fill driver password-selector (System/getenv "GPASS"))
    (e/click driver checkout-button-selector)))

(defn is-item-available?
  "Returns `true` if item is available."
  [driver url]
  (go-if-not-already driver url)
  (e/wait-visible driver {:css ".topoDetalhe-boxRight-nome"})
  (not (e/exists? driver {:tag :a
                          :css ".triggerAviseMe"})))

(defn cart-insert-coupom
  [driver]
  (when-not (= (e/get-url driver) cart-url)
    (e/go driver cart-url)
    (e/wait-exists driver {:css ".carrinhoMain-top"}))
  (e/fill driver {:tag  :input
                  :name "cupom"} "GIGA")
  (e/click driver {:tag  :button
                   :name "btnCupomDesconto"})
  (e/wait driver 2))

(defn- cart-finalize
  "Returns `true` when finalized successfully."
  [driver]
  (when (= (e/get-url driver) cart-url)
    (e/click driver {:tag  :button
                     :type :button
                     :css  ".resumoPedidoBotoes-finalizar"}))

  (let [go-to-payment-selector {:css "div.boxCheckout-selecionarFrete > div:nth-child(2) > a"}]
    (wait-and-click driver go-to-payment-selector))

  (wait-and-click driver {:data-tipo-pag "boleto"})
  (wait-and-click driver [{:tag :form :id "formPagamentoBoleto"} {:tag :button :id "finalizarPedido"}])
  (e/wait-exists driver {:tag :p :css ".codigo-pagamento"})
  true)

(defn request-add-to-cart
  "To add something to cart, simply call a js function with the driver. Expects
  that the product is avialable for purchase."
  [driver product-hash]
  (let [add-to-cart-base-url "https://www.gsuplementos.com.br/tema/growth/ajax/personalizado/geral/ajax-personalizado-geral-adicionar-item-carrinho.php?hash="]
    (e/js-execute driver (format "fetch('%s%s')" add-to-cart-base-url product-hash))))

(defn add-to-cart
  [driver products-url-coll]
  (let [!available   (atom [])
        !unavailable (atom [])]
    (run! (fn [product-url]
            (go-if-not-already driver product-url)
            (if (is-item-available? driver product-url)
              (do
                (request-add-to-cart driver
                                     (e/get-element-attr driver {:tag :a :id "finalizarCompra"} :data-hash))
                (swap! !available conj product-url))
              (swap! !unavailable conj product-url)))

          products-url-coll)
    {:available   @!available
     :unavailable @!unavailable}))

(defn main-flow
  "Open files to check if we bought anything on previous iteration, if not then
  procceed to put items on cart, and finish shopping."
  []
  (e/with-chrome-headless driver

    (when-let [pending-products (seq (keys (cio/load-state-from-file)))]

      (login driver)

      (let [{:keys [available unavailable], :as cart-products} (add-to-cart driver pending-products)]

        ;; if anything is not found, we cancel the iteration
        ;; otherwise, we purchase everything
        (if (seq unavailable)
          (do
            (cio/write-pending-default unavailable)
            (timbre/info "Following items were unavailable: " unavailable))
          (do
            (cart-insert-coupom driver)
            (cart-finalize driver)
            (cio/write-boletado-default available)
            (timbre/info "Following items were boletados: " available)))))))
