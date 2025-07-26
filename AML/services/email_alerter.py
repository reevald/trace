import smtplib
import schedule
import time
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from datetime import datetime, timedelta
from sqlalchemy.orm import Session, joinedload
from database.connection import SessionLocal
from models.transaction import TransactionReview, ReviewStatus, Transaction
from config.settings import EMAIL_HOST, EMAIL_PORT, EMAIL_USER, EMAIL_PASSWORD, EMAIL_TO

class EmailAlerter:
    def __init__(self):
        self.smtp_server = EMAIL_HOST
        self.smtp_port = EMAIL_PORT
        self.email_user = EMAIL_USER
        self.email_password = EMAIL_PASSWORD
        self.email_to = EMAIL_TO
    
    def send_email(self, subject: str, body: str):
        try:
            if not self.email_user or not self.email_password:
                print("Email credentials not configured. Skipping email alert.")
                return False
            
            msg = MIMEMultipart()
            msg['From'] = self.email_user
            msg['To'] = self.email_to
            msg['Subject'] = subject
            
            msg.attach(MIMEText(body, 'html'))
            
            server = smtplib.SMTP(self.smtp_server, self.smtp_port)
            server.starttls()
            server.login(self.email_user, self.email_password)
            
            text = msg.as_string()
            server.sendmail(self.email_user, self.email_to, text)
            server.quit()
            
            print(f"Email alert sent successfully to {self.email_to}")
            return True
            
        except Exception as e:
            print(f"Failed to send email alert: {e}")
            return False
    
    def generate_alert_email(self, suspicious_reviews):
        html_body = f"""
        <html>
        <head>
            <style>
                body {{ font-family: Arial, sans-serif; }}
                .header {{ background-color: #f44336; color: white; padding: 20px; text-align: center; }}
                .content {{ padding: 20px; }}
                .transaction {{ border: 1px solid #ddd; margin: 10px 0; padding: 15px; border-radius: 5px; }}
                .high-risk {{ border-left: 5px solid #f44336; }}
                .medium-risk {{ border-left: 5px solid #ff9800; }}
                .low-risk {{ border-left: 5px solid #4caf50; }}
                .risk-score {{ font-weight: bold; font-size: 18px; }}
                .details {{ margin-top: 10px; font-size: 14px; color: #666; }}
            </style>
        </head>
        <body>
            <div class="header">
                <h1>🚨 AML Alert: Suspicious Transactions Detected</h1>
                <p>Date: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>
            </div>
            
            <div class="content">
                <h2>Summary</h2>
                <p><strong>{len(suspicious_reviews)} suspicious transactions</strong> require immediate review.</p>
                
                <h2>Transaction Details</h2>
        """
        
        for review in suspicious_reviews:
            risk_class = "high-risk" if review.risk_score >= 80 else "medium-risk" if review.risk_score >= 60 else "low-risk"
            
            html_body += f"""
                <div class="transaction {risk_class}">
                    <div class="risk-score">Risk Score: {review.risk_score:.1f}/100</div>
                    <p><strong>Pattern:</strong> {review.pattern_type}</p>
                    <p><strong>Transaction ID:</strong> {review.transaction.tx_id}</p>
                    <p><strong>From:</strong> {review.transaction.from_address}</p>
                    <p><strong>To:</strong> {review.transaction.to_address}</p>
                    <p><strong>Amount:</strong> {review.transaction.amount:,.2f}</p>
                    <p><strong>Date:</strong> {review.transaction.requested_date.strftime('%Y-%m-%d %H:%M:%S')}</p>
                    <div class="details">
                        <strong>Analysis:</strong> {review.analysis_details}
                    </div>
                </div>
            """
        
        html_body += """
                <h2>Next Steps</h2>
                <p>Please log into the AML system to review these transactions:</p>
                <ul>
                    <li>Access the AML dashboard at <a href="http://localhost:8000">http://localhost:8000</a></li>
                    <li>Review each flagged transaction</li>
                    <li>Update the review status after investigation</li>
                    <li>Document your findings in the comment section</li>
                </ul>
                
                <p><em>This is an automated alert from the AML monitoring system.</em></p>
            </div>
        </body>
        </html>
        """
        
        return html_body
    
    def check_and_send_alerts(self):
        print(f"Checking for suspicious transactions to alert at {datetime.now()}")
        
        db = SessionLocal()
        try:
            # Get unreviewed suspicious transactions from the last 24 hours
            yesterday = datetime.utcnow() - timedelta(days=1)
            
            suspicious_reviews = db.query(TransactionReview).options(
                joinedload(TransactionReview.transaction),
                joinedload(TransactionReview.review_status)
            ).filter(
                TransactionReview.is_suspicious == True,
                TransactionReview.created_at >= yesterday
            ).join(ReviewStatus, isouter=True).filter(
                (ReviewStatus.status == "not_reviewed") | (ReviewStatus.status == None)
            ).all()
            
            if suspicious_reviews:
                subject = f"🚨 AML Alert: {len(suspicious_reviews)} Suspicious Transactions Detected"
                body = self.generate_alert_email(suspicious_reviews)
                
                success = self.send_email(subject, body)
                if success:
                    print(f"Alert sent for {len(suspicious_reviews)} suspicious transactions")
                else:
                    print("Failed to send alert email")
            else:
                print("No new suspicious transactions found")
                
        except Exception as e:
            print(f"Error checking for alerts: {e}")
        finally:
            db.close()
    
    def send_daily_summary(self):
        print(f"Sending daily summary at {datetime.now()}")
        
        db = SessionLocal()
        try:
            # Get statistics for the last 24 hours
            yesterday = datetime.utcnow() - timedelta(days=1)
            
            total_transactions = db.query(Transaction).filter(
                Transaction.created_at >= yesterday
            ).count()
            
            suspicious_count = db.query(TransactionReview).filter(
                TransactionReview.created_at >= yesterday,
                TransactionReview.is_suspicious == True
            ).count()
            
            reviewed_count = db.query(ReviewStatus).filter(
                ReviewStatus.reviewed_at >= yesterday,
                ReviewStatus.status == "reviewed"
            ).count()
            
            pending_count = db.query(TransactionReview).join(ReviewStatus, isouter=True).filter(
                TransactionReview.is_suspicious == True,
                (ReviewStatus.status == "not_reviewed") | (ReviewStatus.status == None)
            ).count()
            
            subject = f"📊 Daily AML Summary - {datetime.now().strftime('%Y-%m-%d')}"
            
            body = f"""
            <html>
            <body style="font-family: Arial, sans-serif;">
                <h2>Daily AML Summary</h2>
                <p><strong>Date:</strong> {datetime.now().strftime('%Y-%m-%d')}</p>
                
                <h3>Transaction Statistics (Last 24 Hours)</h3>
                <ul>
                    <li><strong>Total Transactions:</strong> {total_transactions}</li>
                    <li><strong>Suspicious Transactions Detected:</strong> {suspicious_count}</li>
                    <li><strong>Transactions Reviewed:</strong> {reviewed_count}</li>
                    <li><strong>Pending Review:</strong> {pending_count}</li>
                </ul>
                
                <h3>Action Required</h3>
                {"<p>✅ All suspicious transactions have been reviewed.</p>" if pending_count == 0 else f"<p>⚠️ {pending_count} transactions require review.</p>"}
                
                <p><em>This is an automated daily summary from the AML monitoring system.</em></p>
            </body>
            </html>
            """
            
            self.send_email(subject, body)
            
        except Exception as e:
            print(f"Error sending daily summary: {e}")
        finally:
            db.close()

def main():
    alerter = EmailAlerter()
    
    # Schedule alerts every hour
    schedule.every().hour.do(alerter.check_and_send_alerts)
    
    # Schedule daily summary at 9 AM
    schedule.every().day.at("09:00").do(alerter.send_daily_summary)
    
    # Initial check
    alerter.check_and_send_alerts()
    
    print("Email alerter started. Checking for alerts every hour...")
    
    while True:
        schedule.run_pending()
        time.sleep(60)  # Check every minute

if __name__ == "__main__":
    main()