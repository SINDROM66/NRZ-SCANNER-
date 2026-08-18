"""
Verification and Evaluation Test Suite for ug_id_parser.py
Tests both new_id_back.jpg and old_id_back.jpg and verifies field extraction completeness.
"""

import sys
import unittest
from pathlib import Path

import ug_id_parser

class TestUgIdParser(unittest.TestCase):

    def test_new_id_back(self):
        img_path = Path("new_id_back.jpg")
        self.assertTrue(img_path.exists(), "new_id_back.jpg does not exist")
        
        record = ug_id_parser.read_card(str(img_path))
        self.assertIsNotNone(record, "Record should not be None")
        self.assertEqual(record.surname, "MUYUNGA")
        self.assertEqual(record.given_name, "TIMOTHY")
        self.assertEqual(record.sex, "Male")
        self.assertTrue(len(record.card_number) >= 8, f"Invalid card number: {record.card_number}")
        self.assertTrue(len(record.nin) >= 10, f"Invalid NIN: {record.nin}")
        
        print("\n--- new_id_back.jpg Render Output ---")
        print(ug_id_parser.render(record))
        print("\n--- new_id_back.jpg JSON Output ---")
        print(record.to_dict())

    def test_old_id_back(self):
        img_path = Path("old_id_back.jpg")
        self.assertTrue(img_path.exists(), "old_id_back.jpg does not exist")
        
        record = ug_id_parser.read_card(str(img_path))
        self.assertIsNotNone(record, "Record should not be None")
        self.assertEqual(record.surname, "LYOMOKI")
        self.assertEqual(record.given_name, "SAMUEL")
        self.assertEqual(record.other_name, "JUNIOR")
        self.assertTrue(len(record.card_number) >= 8, f"Invalid card number: {record.card_number}")
        self.assertTrue(len(record.nin) >= 10, f"Invalid NIN: {record.nin}")
        
        print("\n--- old_id_back.jpg Render Output ---")
        print(ug_id_parser.render(record))
        print("\n--- old_id_back.jpg JSON Output ---")
        print(record.to_dict())

if __name__ == "__main__":
    unittest.main()
