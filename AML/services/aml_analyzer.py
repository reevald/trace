import networkx as nx
import community as community_louvain
import schedule
import time
from datetime import datetime, timedelta
from sqlalchemy.orm import Session
from database.connection import SessionLocal
from models.transaction import Transaction, TransactionReview, ReviewStatus
from config.settings import AML_THRESHOLD

class AMLAnalyzer:
    def __init__(self):
        self.threshold = AML_THRESHOLD
    
    def build_transaction_graph(self, transactions):
        G = nx.DiGraph()
        
        for tx in transactions:
            G.add_edge(
                tx.from_address, 
                tx.to_address, 
                weight=tx.amount,
                tx_id=tx.tx_id,
                timestamp=tx.requested_date,
                transaction_id=tx.id
            )
        
        return G
    
    def detect_louvain_communities(self, G):
        # Convert to undirected graph for community detection
        G_undirected = G.to_undirected()
        
        # Apply Louvain algorithm
        partition = community_louvain.best_partition(G_undirected)
        
        communities = {}
        for node, community_id in partition.items():
            if community_id not in communities:
                communities[community_id] = []
            communities[community_id].append(node)
        
        return communities, partition
    
    def detect_cycles(self, G, max_cycle_length=7):
        cycles = []
        
        try:
            # Find simple cycles up to max_cycle_length
            for cycle in nx.simple_cycles(G):
                if len(cycle) <= max_cycle_length:
                    cycles.append(cycle)
        except nx.NetworkXError:
            pass
        
        return cycles
    
    def analyze_community_transactions(self, G, community_nodes, community_id):
        suspicious_transactions = []
        
        # Get subgraph for this community
        subgraph = G.subgraph(community_nodes)
        
        # Check for transactions within one year and below threshold
        current_time = datetime.utcnow()
        one_year_ago = current_time - timedelta(days=365)
        
        for edge in subgraph.edges(data=True):
            edge_data = edge[2]
            tx_timestamp = edge_data.get('timestamp')
            tx_amount = edge_data.get('weight', 0)
            
            if (tx_timestamp and tx_timestamp >= one_year_ago and 
                tx_amount < self.threshold):
                
                suspicious_transactions.append({
                    'transaction_id': edge_data.get('transaction_id'),
                    'tx_id': edge_data.get('tx_id'),
                    'from': edge[0],
                    'to': edge[1],
                    'amount': tx_amount,
                    'community_id': community_id,
                    'pattern_type': 'louvain_community',
                    'risk_score': self.calculate_community_risk_score(subgraph, edge_data)
                })
        
        return suspicious_transactions
    
    def analyze_cycle_transactions(self, G, cycles):
        suspicious_transactions = []
        
        for cycle in cycles:
            cycle_edges = []
            total_amount = 0
            
            # Get edges in the cycle
            for i in range(len(cycle)):
                from_node = cycle[i]
                to_node = cycle[(i + 1) % len(cycle)]
                
                if G.has_edge(from_node, to_node):
                    edge_data = G[from_node][to_node]
                    cycle_edges.append({
                        'transaction_id': edge_data.get('transaction_id'),
                        'tx_id': edge_data.get('tx_id'),
                        'from': from_node,
                        'to': to_node,
                        'amount': edge_data.get('weight', 0)
                    })
                    total_amount += edge_data.get('weight', 0)
            
            # Check if cycle involves amounts below threshold
            if total_amount < self.threshold * len(cycle):
                for edge_info in cycle_edges:
                    suspicious_transactions.append({
                        **edge_info,
                        'pattern_type': 'cycle_detection',
                        'cycle_length': len(cycle),
                        'risk_score': self.calculate_cycle_risk_score(len(cycle), total_amount)
                    })
        
        return suspicious_transactions
    
    def calculate_community_risk_score(self, subgraph, edge_data):
        # Simple risk scoring based on community size and transaction patterns
        community_size = len(subgraph.nodes())
        edge_count = len(subgraph.edges())
        amount = edge_data.get('weight', 0)
        
        # Higher risk for larger communities with many small transactions
        base_score = min(90, (community_size * 2) + (edge_count * 1.5))
        
        # Adjust based on amount (lower amounts = higher risk)
        if amount < self.threshold * 0.1:
            base_score += 10
        elif amount < self.threshold * 0.5:
            base_score += 5
        
        return min(100, base_score)
    
    def calculate_cycle_risk_score(self, cycle_length, total_amount):
        # Higher risk for longer cycles and smaller amounts
        base_score = 60 + (cycle_length * 5)
        
        if total_amount < self.threshold * 0.5:
            base_score += 15
        elif total_amount < self.threshold:
            base_score += 10
        
        return min(100, base_score)
    
    def store_analysis_results(self, suspicious_transactions):
        db = SessionLocal()
        try:
            for tx_info in suspicious_transactions:
                # Check if analysis already exists
                existing_review = db.query(TransactionReview).filter(
                    TransactionReview.transaction_id == tx_info['transaction_id'],
                    TransactionReview.pattern_type == tx_info['pattern_type']
                ).first()
                
                if not existing_review:
                    review = TransactionReview(
                        transaction_id=tx_info['transaction_id'],
                        risk_score=tx_info['risk_score'],
                        pattern_type=tx_info['pattern_type'],
                        community_id=tx_info.get('community_id'),
                        cycle_length=tx_info.get('cycle_length'),
                        is_suspicious=True,
                        analysis_details=f"Detected {tx_info['pattern_type']} pattern. "
                                       f"From: {tx_info['from']}, To: {tx_info['to']}, "
                                       f"Amount: {tx_info['amount']}"
                    )
                    db.add(review)
                    db.flush()
                    
                    # Create review status
                    review_status = ReviewStatus(
                        transaction_review_id=review.id,
                        status="not_reviewed"
                    )
                    db.add(review_status)
            
            db.commit()
            print(f"Stored {len(suspicious_transactions)} suspicious transaction analyses")
            
        except Exception as e:
            db.rollback()
            print(f"Error storing analysis results: {e}")
        finally:
            db.close()
    
    def run_analysis(self):
        print(f"Running AML analysis at {datetime.now()}")
        
        db = SessionLocal()
        try:
            # Get recent transactions (last 30 days for analysis)
            thirty_days_ago = datetime.utcnow() - timedelta(days=30)
            transactions = db.query(Transaction).filter(
                Transaction.requested_date >= thirty_days_ago
            ).all()
            
            if not transactions:
                print("No recent transactions found for analysis")
                return
            
            print(f"Analyzing {len(transactions)} transactions")
            
            # Build transaction graph
            G = self.build_transaction_graph(transactions)
            print(f"Built graph with {G.number_of_nodes()} nodes and {G.number_of_edges()} edges")
            
            suspicious_transactions = []
            
            # Louvain community detection
            if G.number_of_nodes() > 1:
                communities, partition = self.detect_louvain_communities(G)
                print(f"Found {len(communities)} communities")
                
                for community_id, nodes in communities.items():
                    if len(nodes) > 2:  # Only analyze communities with more than 2 nodes
                        community_suspicious = self.analyze_community_transactions(
                            G, nodes, community_id
                        )
                        suspicious_transactions.extend(community_suspicious)
            
            # Cycle detection
            cycles = self.detect_cycles(G)
            print(f"Found {len(cycles)} cycles")
            
            if cycles:
                cycle_suspicious = self.analyze_cycle_transactions(G, cycles)
                suspicious_transactions.extend(cycle_suspicious)
            
            # Store results
            if suspicious_transactions:
                self.store_analysis_results(suspicious_transactions)
                print(f"Analysis complete. Found {len(suspicious_transactions)} suspicious patterns")
            else:
                print("No suspicious patterns detected")
                
        except Exception as e:
            print(f"Error during analysis: {e}")
        finally:
            db.close()

def main():
    analyzer = AMLAnalyzer()
    
    # Schedule analysis to run every hour
    schedule.every().hour.do(analyzer.run_analysis)
    
    # Initial analysis
    analyzer.run_analysis()
    
    print("AML analyzer started. Running analysis every hour...")
    
    while True:
        schedule.run_pending()
        time.sleep(60)  # Check every minute

if __name__ == "__main__":
    main()