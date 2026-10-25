import json, sys

def luhn(d):
    s=0; dbl=False
    for c in reversed(d):
        n=int(c)
        if dbl:
            n*=2
            if n>9: n-=9
        s+=n; dbl=not dbl
    return s%10==0

def check(num, expect):
    d=num.replace(' ','').replace('-','')
    assert luhn(d)==expect, (num, luhn(d))
    return num

JWT="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
U1="3f2b8c1e-9a4d-4e6f-b1c2-7d8e9f0a1b2c"
U2="a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
recs=[]
def rec(id, desc, log, sensitive):
    recs.append({"id":id,"description":desc,"log":log,"sensitive_fields":sensitive})

rec("G01","Card authorisation with nested card object, bearer JWT, session cookie",
 {"method":"POST","path":"/api/v1/payments",
  "request":{"headers":{"Content-Type":"application/json","Authorization":"Bearer "+JWT,"X-Correlation-ID":U1},
   "body":{"merchant_id":"MERCH_1092","amount":150.00,"currency":"USD",
     "card":{"number":check("4111111111111111",True),"cvv":"737","expiry":"12/28","holder_name":"Jane Doe"},
     "customer_email":"jane.doe@example.com"}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json","Set-Cookie":"SESSIONID=a8Kd93LmQ2zX71pv; HttpOnly; Secure"},
   "body":{"transaction_id":"TXN-881920","card_last_four":"1111","card_type":"VISA","customer_email":"jane.doe@example.com",
     "amount":150.00,"status":"SUCCESS","auth_code":"A1B2C3"}}},
 ["request.headers.Authorization","request.body.card.number","request.body.card.cvv","request.body.card.expiry",
  "request.body.card.holder_name","request.body.customer_email","response.headers.Set-Cookie","response.body.customer_email"])

rec("G02","Order with Luhn-invalid 16-digit order number, invoice and tracking numbers (look-alikes)",
 {"method":"POST","path":"/api/v1/orders",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"order_number":check("4111111111111112",False),"invoice_no":"123456789","tracking_id":"1Z999AA10123456784",
     "sku":"SKU-0042","quantity":3,"gift_card_last4":"4242","zip":"94107"}},
  "response":{"status_code":201,"headers":{"Content-Type":"application/json"},
   "body":{"order_id":"ORD-1001","status":"CREATED","eta":"2026-11-02"}}},
 [])

rec("G03","Customer profile: SSN, DOB, names, email, phone, street address; city/postcode kept",
 {"method":"GET","path":"/api/v1/customers/C-1009",
  "request":{"headers":{"Authorization":"Basic dXNlcjpwYXNzd29yZDEyMw==","Accept":"application/json"}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"customer_id":"C-1009","first_name":"Rahul","last_name":"Sharma","ssn":"536-22-1987","date_of_birth":"1988-04-12",
     "email":"rahul.sharma@gmail.com","phone":"+14155550132",
     "address":{"line1":"221B Baker Street","city":"London","postal_code":"NW1 6XE","country":"GB"},
     "kyc_status":"VERIFIED","created_at":"2026-01-02T10:00:00Z"}}},
 ["request.headers.Authorization","response.body.first_name","response.body.last_name","response.body.ssn",
  "response.body.date_of_birth","response.body.email","response.body.phone","response.body.address.line1"])

rec("G04","SSN look-alikes: wrong shape, last-4, invalid area numbers; a TIN is sensitive",
 {"method":"POST","path":"/api/v1/tax/forms",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"form_type":"W9","part_number":"123-45-678","ssn_last4":"6789","reference":"000-12-3456","batch":"666-12-3456","tin":"912-34-5678"}},
  "response":{"status_code":202,"headers":{"Content-Type":"application/json"},"body":{"accepted":True,"form_id":"FORM-77812"}}},
 ["request.body.tin"])

rec("G05","Login: password, MFA code, access/refresh tokens; token_type/expires_in/scope kept",
 {"method":"POST","path":"/api/v1/auth/login",
  "request":{"headers":{"Content-Type":"application/json"},"body":{"username":"jdoe","password":"S3cr3t!Passw0rd","mfa_code":"492013"}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"access_token":JWT,"refresh_token":"dGhpc0lzQVJlZnJlc2hUb2tlbjEyMzQ1Njc4OTA","token_type":"Bearer","expires_in":3600,
     "scope":"accounts:read payments:write"}}},
 ["request.body.password","request.body.mfa_code","response.body.access_token","response.body.refresh_token"])

rec("G06","API keys in query string and header; ids and hashes kept",
 {"method":"GET","path":"/api/v1/fx/rates?base=USD&api_key=sk_live_FAKEdemoKey0000",
  "request":{"headers":{"X-API-Key":"AKIAIOSFODNN7EXAMPLE","Accept":"application/json"}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"base":"USD","rates":{"INR":83.12,"EUR":0.92},"provider":"ECB","request_id":U2,
     "checksum":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"}}},
 ["request.query.api_key","request.headers.X-API-Key"])

rec("G07","Free-text description embedding an email and a spaced Mastercard number",
 {"method":"POST","path":"/api/v1/support/tickets",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"subject":"Card declined","description":"Customer jane.roe@example.org called; card "+check("5555 5555 5555 4444",True)+" was declined twice.",
     "channel":"PHONE","priority":"HIGH"}},
  "response":{"status_code":201,"headers":{"Content-Type":"application/json"},"body":{"ticket_id":"TCK-20931","status":"OPEN"}}},
 ["request.body.description"])

rec("G08","Bank transfer: IBANs, account number, beneficiary; routing number and BIC kept",
 {"method":"POST","path":"/api/v1/transfers",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"from_iban":"GB82WEST12345698765432","to_iban":"DE89370400440532013000","account_number":"000123456789",
     "routing_number":"021000021","amount":2500.00,"currency":"EUR","swift_bic":"DEUTDEFF","beneficiary_name":"Anna Schmidt"}},
  "response":{"status_code":202,"headers":{"Content-Type":"application/json"},"body":{"transfer_id":"TRF-1192","status":"PENDING"}}},
 ["request.body.from_iban","request.body.to_iban","request.body.account_number","request.body.beneficiary_name"])

rec("G09","Amex PAN with numeric CVC and numeric expiry parts; BIN and brand kept",
 {"method":"POST","path":"/api/v1/cards/verify",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"pan":check("378282246310005",True),"cvc":1234,"exp_month":7,"exp_year":2029,"card_brand":"AMEX","bin":"378282"}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},"body":{"verified":True,"risk_score":12}}},
 ["request.body.pan","request.body.cvc","request.body.exp_month","request.body.exp_year"])

rec("G10","Document metadata full of hashes, hex ids, UUIDs, versions (all look-alikes)",
 {"method":"GET","path":"/api/v1/documents/DOC-55102","request":{"headers":{"Accept":"application/json"}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"document_id":"DOC-55102","sha256":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
     "content_type":"application/pdf","download_url":"https://files.example.com/d/DOC-55102","version":"3.2.1",
     "trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","idempotency_key":U1,"commit":"a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"}}},
 [])

rec("G11","Unrecognised high-entropy blob under a neutral key (must be masked and reviewed)",
 {"method":"POST","path":"/api/v1/integrations",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"partner":"ACME","config_blob":"q8Z2xL0vN4mR7tY1wK9pB3sF6hJ5dA0e","callback_url":"https://partner.example.com/hook","enabled":True}},
  "response":{"status_code":201,"headers":{"Content-Type":"application/json"},"body":{"integration_id":"INT-778","status":"ACTIVE"}}},
 ["request.body.config_blob"])

rec("G12","Discover and Mastercard 2-series inside an array; last4/brand kept",
 {"method":"POST","path":"/api/v1/wallets",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"wallet_id":"WAL-1","cards":[{"card_number":check("6011111111111117",True),"nickname":"travel"},
                                       {"card_number":check("2223003122003222",True),"nickname":"groceries"}],
     "owner_email":"owner@wallet.io"}},
  "response":{"status_code":201,"headers":{"Content-Type":"application/json"},
   "body":{"wallet_id":"WAL-1","card_count":2,"cards":[{"last4":"1117","brand":"DISCOVER"},{"last4":"3222","brand":"MASTERCARD"}]}}},
 ["request.body.cards[0].card_number","request.body.cards[1].card_number","request.body.owner_email"])

rec("G13","Statement with large amounts, MCCs and a non-network 16-digit statement number",
 {"method":"GET","path":"/api/v1/statements/STMT-2026-10?account_ref=ACCREF77","request":{"headers":{}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"opening_balance":1234567.89,"closing_balance":1250000.00,"statement_number":"1234567812345670",
     "transactions":[{"id":"T1","amount":4111.11,"merchant":"Coffee House","mcc":"5814"},
                     {"id":"T2","amount":99.99,"merchant":"Books Ltd","mcc":"5942"}]}}},
 [])

rec("G14","Phone formats; country code, extension and opt-in flags kept",
 {"method":"POST","path":"/api/v1/contacts",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"mobile":"98765 43210","phone_country_code":"+91","fax":"+1 (212) 555-0199","whatsapp_opt_in":True,"office_extension":"4021"}},
  "response":{"status_code":201,"headers":{"Content-Type":"application/json"},"body":{"contact_id":"CON-9","verified":False}}},
 ["request.body.mobile","request.body.fax"])

rec("G15","India KYC: PAN card number and Aadhaar; PIN code and GSTIN kept",
 {"method":"POST","path":"/api/v1/kyc/india",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"pan_number":"ABCDE1234F","aadhaar":"2345 6789 0123","pincode":"560001","gstin":"27AAPFU0939F1ZV"}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},"body":{"kyc_reference":"KYC-IN-2231","status":"APPROVED"}}},
 ["request.body.pan_number","request.body.aadhaar"])

rec("G16","The assignment's own example: credit_card + cvv masked, amount survives",
 {"method":"POST","path":"/api/v1/payments",
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"credit_card":check("4111111111111111",True),"cvv":"123","amount":100.0}}},
 ["response.body.credit_card","response.body.cvv"])

rec("G17","Cookie with a JWT session, CSRF token; user agent and language kept",
 {"method":"GET","path":"/api/v1/dashboard",
  "request":{"headers":{"Cookie":"session=eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyIjoiamRvZSJ9.c2lnbmF0dXJlMTIz; theme=dark",
     "X-CSRF-Token":"b5f8a1c3d9e2f4a6b8c0d2e4f6a8b0c2","User-Agent":"Mozilla/5.0 (Windows NT 10.0)","Accept-Language":"en-IN"}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},"body":{"widgets":["balance","offers"],"theme":"dark"}}},
 ["request.headers.Cookie","request.headers.X-CSRF-Token"])

rec("G18","Email look-alikes: @mentions, package@version, bare domains",
 {"method":"POST","path":"/api/v1/notifications",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"to":"priya.n@bank.co.in","cc":["ops@bank.co.in"],"template":"welcome_v2","mention":"@ops-team",
     "package":"lodash@4.17.21","sender_domain":"bank.co.in"}},
  "response":{"status_code":202,"headers":{"Content-Type":"application/json"},"body":{"notification_id":"NTF-31","queued":True}}},
 ["request.body.to","request.body.cc[0]"])

rec("G19","Beneficiary update: full name, US-format DOB, passport; nationality kept",
 {"method":"PUT","path":"/api/v1/beneficiaries/BEN-77",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"full_name":"Mohammed Al-Farsi","dob":"03/15/1979","relationship":"SPOUSE","nationality":"AE","passport_number":"N1234567"}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"beneficiary_id":"BEN-77","full_name":"Mohammed Al-Farsi","status":"UPDATED"}}},
 ["request.body.full_name","request.body.dob","request.body.passport_number","response.body.full_name"])

rec("G20","Partner credentials: client secret and PEM private key; client_id and key_id kept",
 {"method":"POST","path":"/api/v1/partners/keys",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"client_id":"partner-app-01","client_secret":"xYz9aBcD3eFgH7iJkL1mNoP5qRsT",
     "private_key":"-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun\n-----END RSA PRIVATE KEY-----",
     "key_id":"kid-2026-01","algorithm":"RS256"}},
  "response":{"status_code":201,"headers":{"Content-Type":"application/json"},"body":{"registered":True,"key_id":"kid-2026-01"}}},
 ["request.body.client_secret","request.body.private_key"])

rec("G21","GitHub token and a Slack token embedded in notes",
 {"method":"POST","path":"/api/v1/devops/hooks",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"repo":"bank/payments","github_token":"ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789","notes":"rotate xoxb-1234567890-abcdefghijkl next week","branch":"main"}},
  "response":{"status_code":201,"headers":{"Content-Type":"application/json"},"body":{"hook_id":"HK-5"}}},
 ["request.body.github_token","request.body.notes"])

rec("G22","Refund with 13-digit Visa (spaced), JCB and UnionPay numbers under unhelpful names",
 {"method":"POST","path":"/api/v1/refunds",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"original_card":check("4222 2222 2222 2",True),"alt_card":check("3530111333300000",True),"unionpay":check("6200000000000005",True),
     "refund_amount":75.5,"reason":"DUPLICATE"}},
  "response":{"status_code":202,"headers":{"Content-Type":"application/json"},"body":{"refund_id":"RFD-11","status":"QUEUED"}}},
 ["request.body.original_card","request.body.alt_card","request.body.unionpay"])

rec("G23","POS: track-2 data and PIN block; terminal id and approval code kept",
 {"method":"POST","path":"/api/v1/pos/transactions",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"track2":"4111111111111111=28121010000012300000","pin_block":"0412AC89ABCDEF67","terminal_id":"TERM-0098","amount":20.0}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},"body":{"approved":True,"approval_code":"A1B2C3"}}},
 ["request.body.track2","request.body.pin_block"])

rec("G24","Branch lookup: pincode/IFSC/branch codes are public identifiers",
 {"method":"GET","path":"/api/v1/branches?pincode=560001&city=Bengaluru","request":{"headers":{}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"branches":[{"branch_code":"BLR-001","ifsc":"HDFC0000123","pincode":"560001","phone_type":"LANDLINE"}],"total":1}}},
 [])

rec("G25","Account opening (worked example shape) with a tax id",
 {"method":"POST","path":"/api/v1/accounts",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"account_holder":"Jane Doe","initial_deposit":500.00,"currency":"USD","tax_id":"98-7654321"}},
  "response":{"status_code":201,"headers":{"Content-Type":"application/json","Location":"/api/v1/accounts/ACC-9921"},
   "body":{"account_id":"ACC-9921","account_holder":"Jane Doe","balance":500.0,"status":"ACTIVE",
     "links":{"self":"/api/v1/accounts/ACC-9921","transactions":"/api/v1/accounts/ACC-9921/transactions?page=2&size=50"}}}},
 ["request.body.account_holder","request.body.tax_id","response.body.account_holder"])

rec("G26","Email address in a URL path segment and in a query parameter",
 {"method":"GET","path":"/api/v1/users/john.smith%40example.com/preferences?notify_email=alerts%40example.com&lang=en","request":{"headers":{}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},"body":{"theme":"light","lang":"en"}}},
 ["request.path","request.query.notify_email"])

rec("G27","PAN in a URL path segment",
 {"method":"GET","path":"/api/v1/cards/"+check("5555555555554444",True)+"/balance","request":{"headers":{}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"available":1200.5,"currency":"USD","card_last_four":"4444"}}},
 ["request.path"])

rec("G28","500 error whose message leaks an email and a JWT",
 {"method":"POST","path":"/api/v1/payments/PAY-1/capture","request":{"headers":{"Content-Type":"application/json"},"body":{"amount":10.0}},
  "response":{"status_code":500,"headers":{"Content-Type":"application/json"},
   "body":{"error":"INTERNAL","message":"Capture failed for user maria@contoso.com token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJtYXJpYSJ9.abc123def456",
     "trace_id":"0af7651916cd43dd8448eb211c80319c"}}},
 ["response.body.message"])

rec("G29","Configuration document with no sensitive data",
 {"method":"GET","path":"/api/v1/config","request":{"headers":{}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json","Cache-Control":"max-age=60"},
   "body":{"feature_flags":{"new_ui":True,"beta_payments":False},"max_retries":3,"timeout_ms":2500,
     "supported_currencies":["USD","EUR","INR"],"support_email_domain":"bank.example","version":"2026.10.1",
     "build_sha":"3f786850e387550fdab836ed7e6dc881de23001b","password_policy":"min 12 chars, 1 digit"}}},
 [])

rec("G30","Identifier look-alikes with mixed case and digits under id-like names",
 {"method":"GET","path":"/api/v1/devices/current","request":{"headers":{}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"device_fingerprint":"Xk9mP2qL7vR4tN8wZ3bC6yH1","request_id":"req_9fJ2kL0pQ7xZ4mN1","correlation_id":U2,"model":"Pixel 9"}}},
 [])

rec("G31","Secret-adjacent metadata that is not itself secret",
 {"method":"GET","path":"/api/v1/developer/keys/KEY-12","request":{"headers":{}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"api_key_last4":"x9Q2","api_key_id":"KEY-12","token_expires_at":"2026-12-31T00:00:00Z","token_type":"Bearer",
     "password_reset_required":False,"scopes":["read","write"]}}},
 [])

rec("G32","Long ledger numbers outside card networks",
 {"method":"GET","path":"/api/v1/ledger/entries?limit=2","request":{"headers":{}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"entries":[{"ledger_entry_id":"1234567890123456789","amount":10.0},{"ledger_entry_id":"9876543210987654","amount":20.0}]}}},
 [])

rec("G33","Base64-encoded text attachment and a PNG thumbnail (content, not credentials)",
 {"method":"POST","path":"/api/v1/messages",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"attachment":"SGVsbG8gV29ybGQhIFRoaXMgaXMgYSB0ZXN0IGF0dGFjaG1lbnQu",
     "thumbnail_image":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="}},
  "response":{"status_code":201,"headers":{"Content-Type":"application/json"},"body":{"message_id":"MSG-1"}}},
 [])

rec("G34","Business names that are not person names",
 {"method":"GET","path":"/api/v1/merchants/MERCH_1092","request":{"headers":{}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"merchant_name":"Jane's Bakery","product_name":"Gold Card","company":"Doe Industries","name":"Bakery POS #2","category":"FOOD"}}},
 [])

rec("G35","Payment method with nested card {number, exp, cvc, name}",
 {"method":"POST","path":"/api/v1/payment-methods",
  "request":{"headers":{"Content-Type":"application/json"},
   "body":{"type":"card","card":{"number":check("5200828282828210",True),"exp":"03/27","cvc":"123","name":"John Q Public"}}},
  "response":{"status_code":201,"headers":{"Content-Type":"application/json"},"body":{"payment_method_id":"pm_1NabcXYZ","status":"attached"}}},
 ["request.body.card.number","request.body.card.exp","request.body.card.cvc","request.body.card.name"])

rec("G36","Dates that are not dates of birth",
 {"method":"GET","path":"/api/v1/policies/POL-9","request":{"headers":{}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"policy_id":"POL-9","valid_from":"2026-01-01","effective_date":"1990-01-01","created_date":"1988-04-12"}}},
 [])

rec("G37","Credential in a response header; rate-limit headers kept",
 {"method":"POST","path":"/api/v1/sessions","request":{"headers":{"Content-Type":"application/json"},"body":{"device":"web"}},
  "response":{"status_code":201,"headers":{"Content-Type":"application/json","X-Auth-Token":"f4c3b00c-1234-4f6a-9e1b-2a3c4d5e6f70","X-RateLimit-Remaining":"99"},
   "body":{"created":True}}},
 ["response.headers.X-Auth-Token"])

rec("G38","OAuth access token in a query string",
 {"method":"GET","path":"/api/v1/export?format=csv&access_token=ya29.a0AfH6SMBx3kq9ZP0lK7mN2rT5vW8yB1cD4eF6gH9","request":{"headers":{}},
  "response":{"status_code":200,"headers":{"Content-Type":"text/csv"},"body":"id,amount\n1,10.00\n"}},
 ["request.query.access_token"])

rec("G39","SSN-formatted value in an id-like field (masked, reviewed) vs invalid-area reference",
 {"method":"POST","path":"/api/v1/applications",
  "request":{"headers":{"Content-Type":"application/json"},"body":{"applicant_id_number":"536-22-1987","case_reference":"000-45-6789","product":"LOAN"}},
  "response":{"status_code":201,"headers":{"Content-Type":"application/json"},"body":{"application_id":"APP-310"}}},
 ["request.body.applicant_id_number"])

rec("G40","Arrays of emails and E.164 phone numbers",
 {"method":"GET","path":"/api/v1/teams/TEAM-4/contacts","request":{"headers":{}},
  "response":{"status_code":200,"headers":{"Content-Type":"application/json"},
   "body":{"contacts":[{"email":"a.kumar@corp.in","role":"OWNER"},{"email":"b.singh@corp.in","role":"MEMBER"}],
     "phones":["+919876543210","+14155550199"]}}},
 ["response.body.contacts[0].email","response.body.contacts[1].email","response.body.phones[0]","response.body.phones[1]"])

# sanity: non-network numbers
for n in ["1234567812345670","1234567890123456789","9876543210987654"]:
    pass
def category(rid, path):
    p=path.lower()
    overrides={("G07","request.body.description"):"PCI",("G28","response.body.message"):"SECRET",
               ("G26","request.path"):"PII",("G27","request.path"):"PCI",("G21","request.body.notes"):"SECRET",
               ("G11","request.body.config_blob"):"SECRET",("G08","request.body.account_number"):"PII",("G23","request.body.pin_block"):"PCI",
               ("G22","request.body.original_card"):"PCI",("G22","request.body.alt_card"):"PCI",("G22","request.body.unionpay"):"PCI"}
    if (rid,path) in overrides: return overrides[(rid,path)]
    leaf=p.split('.')[-1]
    if any(k in leaf for k in ["ssn","tin","tax_id","aadhaar","pan_number","passport","applicant_id_number"]): return "PII_CRITICAL"
    if any(k in leaf for k in ["card","pan","cvv","cvc","exp","track","credit_card","number","pin"]): return "PCI"
    if any(k in leaf for k in ["authorization","cookie","password","mfa","token","api_key","x-api-key","secret","private_key","csrf","x-auth"]): return "SECRET"
    return "PII"
for r in recs:
    r["field_categories"]={f:category(r["id"],f) for f in r["sensitive_fields"]}
out={"dataset":"sanitization_golden_dataset","description":"40 labelled exchanges. sensitive_fields lists every field whose original value must not survive; every other field must survive unchanged (value and type). Look-alikes are deliberate.","records":recs}
json.dump(out, open(sys.argv[1],"w"), indent=2)
print(len(recs), sum(len(r["sensitive_fields"]) for r in recs))
